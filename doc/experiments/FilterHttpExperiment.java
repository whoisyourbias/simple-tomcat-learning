import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import simpletomcat.examples.FilterServer;

/** 생성 JAR의 실제 필터 구성에 curl 요청을 보내고 해제 순서·횟수·포트 반환을 확인한다. */
public class FilterHttpExperiment {
    public static void main(String[] args) throws Exception {
        var trace = new CopyOnWriteArrayList<String>(); var engine = FilterServer.createEngine(trace::add);
        var protocol = FilterServer.open(engine,0); int port = protocol.getPort();
        ExecutorService worker = Executors.newSingleThreadExecutor(); var entered = new CountDownLatch(1);
        Future<?> serving = worker.submit(() -> { entered.countDown(); protocol.serve(); return null; });
        try {
            check(entered.await(3,TimeUnit.SECONDS),"start timeout");
            String[][] cases = {
                {"GET","/shop/hello","200","shop hello\n"}, {"GET","/admin/hello","200","admin hello\n"},
                {"GET","/shop/blocked","200","blocked\n"}, {"GET","/shop/filter-fail","500","500 Internal Server Error\n"},
                {"GET","/shop/hello","200","shop hello\n"}, {"GET","/shop/servlet-fail","500","500 Internal Server Error\n"},
                {"GET","/shop/hello","200","shop hello\n"}, {"HEAD","/shop/hello","200",""},
                {"GET","/shop/WEB-INF/config","404",""}
            };
            for (String[] sample : cases) {
                int priorServices = Collections.frequency(trace,"shop-service");
                String curl = System.getenv("SystemRoot") == null ? "curl" : Path.of(System.getenv("SystemRoot"),"System32","curl.exe").toString();
                var command = new ArrayList<>(List.of(curl,"--silent","--show-error","--include","--noproxy","*","--max-time","3"));
                if (sample[0].equals("HEAD")) command.add("--head");
                command.add("http://127.0.0.1:" + port + sample[1]);
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try {
                    check(process.waitFor(5,TimeUnit.SECONDS),"curl timeout");
                    String wire = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                    check(process.exitValue()==0,"curl failure " + wire);
                    check(wire.startsWith("HTTP/1.1 " + sample[2] + " "),"status " + wire);
                    check(wire.substring(wire.indexOf("\r\n\r\n")+4).equals(sample[3]),"body " + wire);
                    if (sample[1].equals("/shop/blocked")) check(priorServices==Collections.frequency(trace,"shop-service"),"blocked service");
                    System.out.println(sample[0] + " " + sample[1] + " -> " + sample[2]);
                } finally { if (process.isAlive()) process.destroyForcibly(); }
            }
        } finally {
            try { protocol.close(); serving.get(5,TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow(); check(worker.awaitTermination(5,TimeUnit.SECONDS),"worker stop");
                FilterServer.closeApplications(engine); FilterServer.closeApplications(engine);
            }
        }
        for (String name : List.of("A","B")) {
            check(Collections.frequency(trace,name + "-init")==1,"init count");
            check(Collections.frequency(trace,name + "-destroy")==1,"destroy count");
        }
        check(trace.indexOf("shop-destroy")<trace.indexOf("B-destroy"),"servlet before filter");
        check(trace.indexOf("B-destroy")<trace.indexOf("A-destroy"),"reverse filter stop");
        try (ServerSocket rebound = new ServerSocket()) { rebound.setReuseAddress(true); rebound.bind(new InetSocketAddress(port)); }
        System.out.println("PASS: 9 curl cases, filter lifecycle, repeated cleanup, port rebind");
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
