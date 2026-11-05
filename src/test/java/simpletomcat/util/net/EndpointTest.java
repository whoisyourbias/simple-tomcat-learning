package simpletomcat.util.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 TCP 연결로 순차 서버의 응답·오류 복구·종료·자원 반환을 검증한다.
 */
class EndpointTest {
    private static final String BODY = "안녕하세요, simple-tomcat!\n"; // 서버와 일치해야 하는 본문을 독립적인 기대값으로 둔다.

    /**
     * 서로 다른 두 연결에서 동일한 정상 응답을 받고 각 연결이 닫히는지 검증한다.
     * @throws Exception 소켓 통신이나 서버 작업 대기에 실패한 경우
     */
    // 임의 포트 서버에 두 연결로 요청하면 각각 200·UTF-8 바이트 길이·고정 본문·EOF를 받아야 한다.
    @Test
    void respondsToTwoConnections() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            for (int i = 0; i < 2; i++) { // 같은 서버에서 연결을 새로 만들어 두 요청을 순서대로 보낸다.
                assertResponse(request(server.port(), "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")); // 새 연결의 전체 응답을 읽어 상태·헤더·본문을 검사한다.
            }
        }
    }

    /**
     * 분할된 헤더 종료 표식이 완성되기 전에는 응답하지 않는지 검증한다.
     * @throws Exception 소켓 통신이나 서버 대기 상태 확인에 실패한 경우
     */
    // 헤더 끝을 분할 전송하면 마지막 LF 전에는 응답하지 않고 완성 뒤 정확한 응답을 받아야 한다.
    @Test
    void waitsForSplitHeaderTerminator() throws Exception {
        try (RunningServer server = new RunningServer(); Socket client = connect(server.port())) { // 서버와 클라이언트 소켓을 함께 소유하고 블록 종료 때 모두 닫는다.
            send(client, "GET / HTTP/1.1\r\nHost: localhost\r\n\r"); // 헤더 종료 표식의 마지막 LF를 제외하고 전송한다.
            server.awaitBlockedIn("readHeaders"); // 서버가 연결을 수락해 헤더 읽기에 진입한 뒤 다음 동작을 수행한다.
            client.setSoTimeout(200); // 미완성 헤더 동안 응답이 없는지 200ms의 유한 관찰 구간을 설정한다.
            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read()); // 응답 바이트나 EOF 대신 읽기 제한 시간 초과가 발생해야 한다.
            client.setSoTimeout(3000); // 완성 후 응답을 읽을 때 사용할 3초 제한으로 되돌린다.
            send(client, "\n"); // 마지막 LF를 보내 CRLFCRLF를 완성한다.
            assertResponse(client.getInputStream().readAllBytes()); // 서버 EOF까지 응답을 읽고 고정 응답 계약을 확인한다.
        }
    }

    /**
     * 미완성 요청의 EOF에는 무응답으로 종료하고 다음 요청은 처리하는지 검증한다.
     * @throws Exception 소켓 통신이나 서버 작업 대기에 실패한 경우
     */
    // 미완성 헤더 뒤 송신 EOF를 보내면 무응답으로 닫고 다음 정상 연결은 처리해야 한다.
    @Test
    void recoversAfterIncompleteEof() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            try (Socket client = connect(server.port())) { // 이번 시나리오의 클라이언트를 연결하고 종료 시 소켓을 닫는다.
                send(client, "GET / HTTP/1.1\r\nHost: localhost\r\n"); // 마지막 빈 줄이 없는 미완성 헤더를 전송한다.
                client.shutdownOutput(); // 클라이언트 송신만 닫아 서버에는 EOF를 보내고 수신은 유지한다.
                assertEquals(-1, client.getInputStream().read()); // 응답 데이터 없이 연결이 닫혀 EOF가 오는지 확인한다.
            }
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n")); // 새 연결의 정상 응답으로 서버가 계속 요청을 처리함을 확인한다.
        }
    }

    /**
     * 문법을 검사하지 않는 1단계의 고정 응답 정책을 명시적으로 검증한다.
     * @throws Exception 소켓 통신이나 서버 작업 대기에 실패한 경우
     */
    // HTTP 문법이나 Host가 잘못돼도 헤더 끝이 있으면 1단계에서는 고정 200 응답을 받아야 한다.
    @Test
    void respondsWithoutValidatingHttp() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            assertResponse(request(server.port(), "not-http\r\n\r\n")); // HTTP 문법이 틀려도 종료 표식이 있으면 200을 받는 현재 정책을 확인한다.
        }
    }

    /**
     * 클라이언트의 연결 강제 종료가 다음 연결 처리를 막지 않는지 검증한다.
     * @throws Exception 소켓 통신이나 서버 대기 상태 확인에 실패한 경우
     */
    // 헤더 읽기 중 클라이언트가 RST로 끊어도 다음 정상 요청을 처리해야 한다.
    @Test
    void recoversAfterConnectionReset() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            try (Socket client = connect(server.port())) { // 이번 시나리오의 클라이언트를 연결하고 종료 시 소켓을 닫는다.
                send(client, "GET / HTTP/1.1\r\n"); // 서버가 계속 읽어야 하는 미완성 요청을 전송한다.
                server.awaitBlockedIn("readHeaders"); // 서버가 연결을 수락해 헤더 읽기에 진입한 뒤 다음 동작을 수행한다.
                client.setSoLinger(true, 0); // 이 소켓이 닫힐 때 RST를 보내 서버의 연결 오류 경로를 실행한다.
            }
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n")); // 새 연결의 정상 응답으로 서버가 계속 요청을 처리함을 확인한다.
        }
    }

    /**
     * 수락 대기의 취소·반복 종료·동일 포트 재사용을 검증한다.
     * @throws Exception 서버 작업 대기나 포트 재바인딩에 실패한 경우
     */
    // accept 대기 중 close를 두 번 호출하면 serve가 끝나고 같은 포트를 다시 바인딩할 수 있어야 한다.
    @Test
    void closesWhileAcceptingAndReleasesPort() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            server.awaitBlockedIn("serve"); // serve가 수신 소켓의 수락 대기에 진입했는지 확인한다.
            server.stop(); // 서버 소켓을 닫고 실행 작업이 끝날 때까지 유한 시간 기다린다.
            server.endpoint.close(); // 이미 닫힌 Endpoint에 close를 다시 호출해 반복 종료를 확인한다.
            assertPortReleased(server.port()); // 동일 포트에 새 수신 소켓을 바인딩하여 포트 반환을 확인한다.
        }
    }

    /**
     * 헤더 읽기 대기의 취소·클라이언트 EOF·포트 반환을 검증한다.
     * @throws Exception 소켓 통신·서버 대기·재바인딩에 실패한 경우
     */
    // 미완성 헤더를 읽는 중 close하면 연결 EOF와 serve 종료, 포트 반환을 확인해야 한다.
    @Test
    void closesWhileReading() throws Exception {
        try (RunningServer server = new RunningServer(); Socket client = connect(server.port())) { // 서버와 클라이언트 소켓을 함께 소유하고 블록 종료 때 모두 닫는다.
            send(client, "GET / HTTP/1.1\r\n"); // 서버가 계속 읽어야 하는 미완성 요청을 전송한다.
            server.awaitBlockedIn("readHeaders"); // 서버가 연결을 수락해 헤더 읽기에 진입한 뒤 다음 동작을 수행한다.
            server.stop(); // 서버 소켓을 닫고 실행 작업이 끝날 때까지 유한 시간 기다린다.
            assertEquals(-1, client.getInputStream().read()); // 응답 데이터 없이 연결이 닫혀 EOF가 오는지 확인한다.
            assertPortReleased(server.port()); // 동일 포트에 새 수신 소켓을 바인딩하여 포트 반환을 확인한다.
        }
    }

    /**
     * 시작 전 종료한 Endpoint가 수락 대기 없이 반환하는지 검증한다.
     * @throws Exception Endpoint 생성·정리·재바인딩에 실패한 경우
     */
    // serve 전에 close하면 serve는 반환하고 반복 close도 가능하며 포트가 반환돼야 한다.
    @Test
    void closesBeforeServing() throws Exception {
        try (Endpoint endpoint = new Endpoint(0)) { // 실행 루프를 시작하지 않고 임의 포트에 바인딩한 서버만 생성한다.
            int port = endpoint.getPort(); // 종료 뒤 재바인딩할 실제 포트를 기억한다.
            endpoint.close(); // Endpoint가 소유한 수신 소켓과 현재 연결을 정리한다.
            endpoint.serve(); // 이미 닫은 객체의 첫 serve 호출이 수락 대기 없이 반환하는지 확인한다.
            endpoint.close(); // 시작 전에 종료한 객체도 close를 반복 호출할 수 있는지 확인한다.
            assertPortReleased(port); // 실행 전 종료에서도 포트를 다시 사용할 수 있는지 확인한다.
        }
    }

    /**
     * 중복 serve 호출은 거부하되 기존 서버는 요청에 응답하는지 검증한다.
     * @throws Exception 소켓 통신이나 서버 작업 대기에 실패한 경우
     */
    // 이미 실행 중인 서버에서 serve를 다시 호출하면 기존 루프를 방해하지 않고 거부해야 한다.
    @Test
    void rejectsSecondServe() throws Exception {
        try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
            assertThrows(IllegalStateException.class, server.endpoint::serve); // 기존 serve가 실행 중일 때 두 번째 실행이 거부되는지 확인한다.
            assertResponse(request(server.port(), "GET / HTTP/1.1\r\n\r\n")); // 새 연결의 정상 응답으로 서버가 계속 요청을 처리함을 확인한다.
        }
    }

    /**
     * 연결 시도와 서버 종료를 반복 경합시켜 남는 연결이나 작업이 없는지 검증한다.
     * @throws Exception 작업 실행·대기·자원 정리에 실패한 경우
     */
    // 연결 시도와 close를 같은 latch로 시작해 경합시켜도 serve와 클라이언트가 유한 시간 안에 끝나야 한다.
    @Test
    void closesDuringConnectionRace() throws Exception {
        ExecutorService clients = Executors.newSingleThreadExecutor(); // 종료 요청과 경합시킬 클라이언트 작업자를 따로 준비한다.
        try { // 경합 실험 중 실패하더라도 finally에서 클라이언트 작업자를 종료한다.
            for (int i = 0; i < 30; i++) { // 경합을 30회 반복하되 모든 스레드 스케줄을 검증한다고 간주하지 않는다.
                try (RunningServer server = new RunningServer()) { // 임의 포트 서버를 준비하고 테스트 종료 시 서버와 작업자를 정리한다.
                    CountDownLatch go = new CountDownLatch(1); // 클라이언트의 연결 시도를 해제할 공통 시작 신호를 만든다.
                    Future<?> client = clients.submit(() -> { // 연결 시도를 별도 작업에서 실행하고 완료 여부를 Future로 추적한다.
                        assertTrue(go.await(3, TimeUnit.SECONDS)); // 시작 신호를 최대 3초 기다리며 신호 누락은 테스트 실패로 처리한다.
                        try (Socket socket = connect(server.port())) { // 서버에 연결하고 성공한 소켓은 반드시 정리한다.
                            try { // EOF·읽기 시간 초과·RST를 구별하여 종료 결과를 확인한다.
                                assertEquals(-1, socket.getInputStream().read()); // 요청을 보내지 않은 연결도 서버 종료 후 EOF로 끝나야 한다.
                            } catch (SocketTimeoutException timeout) { // 서버 종료 뒤 연결이 계속 남아 있는 상황을 따로 실패 처리한다.
                                throw new AssertionError("종료 후 연결이 남음", timeout); // 종료 누락을 정상적인 연결 취소로 오인하지 않도록 실패시킨다.
                            } catch (java.net.SocketException reset) { // 종료 경합으로 연결이 강제 종료된 결과도 허용한다.
                                // 종료와 경합한 연결은 RST로 끝날 수도 있다.
                            }
                        } catch (java.net.ConnectException refused) { // 수신 소켓이 먼저 닫혀 접속 자체가 거부된 결과도 허용한다.
                            // 수신 소켓 종료가 먼저면 연결이 거부된다.
                        }
                        return null; // 반환값 없는 Callable 작업의 정상 완료를 나타낸다.
                    });
                    go.countDown(); // 클라이언트 대기를 해제하고 곧바로 서버 종료를 진행해 경합시킨다.
                    server.stop(); // 서버 소켓을 닫고 실행 작업이 끝날 때까지 유한 시간 기다린다.
                    client.get(5, TimeUnit.SECONDS); // 클라이언트가 최대 5초 안에 끝나는지 확인하며 작업 예외도 전달받는다.
                    assertPortReleased(server.port()); // 동일 포트에 새 수신 소켓을 바인딩하여 포트 반환을 확인한다.
                }
            }
        } finally { // 앞선 동작의 성공 여부와 관계없이 소유한 자원을 정리한다.
            clients.shutdownNow(); // 추가 클라이언트 작업을 막고 남은 작업에 중단을 요청한다.
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS)); // 클라이언트 작업자 스레드가 최대 5초 안에 종료되는지 확인한다.
        }
    }

    /**
     * 3초 연결·읽기 제한을 갖는 루프백 클라이언트를 생성한다.
     * @param port 접속할 서버 포트
     * @return 연결된 소켓(닫기는 호출자 책임)
     * @throws IOException 연결 또는 소켓 옵션 설정에 실패한 경우
     */
    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket(); // 설정을 적용하고 실패 시 직접 정리할 클라이언트 소켓을 만든다.
        try { // 연결과 옵션 설정이 끝나기 전에 실패하면 catch에서 소켓을 정리한다.
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000); // 루프백의 지정 포트에 최대 3초 동안 연결을 시도한다.
            socket.setSoTimeout(3000); // 서버 응답이 없더라도 각 읽기가 3초를 넘겨 대기하지 않게 한다.
            return socket; // 연결된 소켓의 소유권을 호출자에게 넘긴다.
        } catch (IOException failure) { // 연결 또는 읽기 옵션 설정의 실패를 정리한 뒤 전달한다.
            socket.close(); // 호출자에게 넘기지 못한 소켓을 닫는다.
            throw failure; // 호출자가 연결 준비의 원래 실패를 확인하도록 전파한다.
        }
    }

    /**
     * 요청 문자열을 ASCII 바이트로 전송하고 출력 스트림을 비운다.
     * @param socket 요청을 보낼 연결(이 메서드에서 닫지 않는다)
     * @param request CRLF를 포함한 원시 요청 문자열
     * @throws IOException 쓰기 또는 flush에 실패한 경우
     */
    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); // 원시 HTTP 요청을 ASCII 바이트로 변환해 전송한다.
        socket.getOutputStream().flush(); // 출력 스트림에 남은 요청 데이터가 있다면 내보낸다.
    }

    /**
     * 새 연결로 요청 하나를 보내고 서버가 연결을 닫을 때까지 응답을 읽는다.
     * @param port 접속할 서버 포트
     * @param request 전송할 원시 요청 문자열
     * @return EOF까지 읽은 응답 바이트
     * @throws IOException 연결·전송·읽기·소켓 정리에 실패한 경우
     */
    private static byte[] request(int port, String request) throws IOException {
        try (Socket socket = connect(port)) { // 응답 수신 성공이나 실패와 관계없이 이번 요청의 클라이언트 연결을 닫는다.
            send(socket, request); // 열린 연결로 요청 문자열을 전송한다.
            return socket.getInputStream().readAllBytes(); // 읽기 제한을 적용한 소켓에서 서버 EOF까지 응답을 모아 반환한다.
        }
    }

    /**
     * 상태·헤더·UTF-8 본문이 고정 응답 계약과 일치하는지 검증한다.
     * @param response EOF까지 읽은 전체 응답
     * @throws AssertionError 상태·헤더·본문이 기대값과 다른 경우
     */
    private static void assertResponse(byte[] response) {
        String message = new String(response, StandardCharsets.UTF_8); // 본문의 한글을 포함한 전체 응답을 UTF-8 문자열로 해석한다.
        int boundary = message.indexOf("\r\n\r\n"); // 헤더와 본문을 나누는 첫 빈 줄의 시작 위치를 찾는다.
        assertTrue(boundary >= 0, message); // 헤더 종료 표식이 없으면 전체 응답을 실패 메시지로 보여 준다.
        String headers = message.substring(0, boundary); // 본문을 제외한 상태 행과 헤더만 추출한다.
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"), headers); // 정확한 프로토콜·상태 코드·이유 구문으로 시작하는지 확인한다.
        assertTrue(headers.contains("Content-Type: text/plain; charset=UTF-8"), headers); // 본문의 미디어 타입과 UTF-8 인코딩 안내를 확인한다.
        assertTrue(Arrays.asList(headers.split("\r\n")).contains( // 헤더를 행 단위로 나누어 Content-Length의 부분 일치를 방지한다.
                "Content-Length: " + BODY.getBytes(StandardCharsets.UTF_8).length), headers); // 문자 수 대신 UTF-8 바이트 수와 헤더 전체 행이 일치해야 한다.
        assertTrue(headers.contains("Connection: close"), headers); // 이번 응답 뒤 연결을 닫는다는 명시적인 헤더를 확인한다.
        assertEquals(BODY, message.substring(boundary + 4)); // CRLFCRLF 네 문자를 건너뛰고 본문 전체가 기대값과 같은지 확인한다.
    }

    /**
     * 기존 포트에 새 수신 소켓을 바인딩해 포트 반환을 확인한다.
     * @param port 종료한 서버가 사용했던 포트
     * @throws IOException 소켓 생성·설정·바인딩·정리에 실패한 경우
     */
    private static void assertPortReleased(int port) throws IOException {
        try (ServerSocket rebound = new ServerSocket()) { // 반환된 포트를 검사할 임시 수신 소켓을 만들고 검사 후 닫는다.
            rebound.setReuseAddress(true); // 검증 대상과 같은 주소 재사용 옵션을 바인딩 전에 적용한다.
            rebound.bind(new InetSocketAddress(port)); // 기존 서버와 같은 wildcard 주소와 포트에 실제로 바인딩한다.
            assertEquals(port, rebound.getLocalPort()); // 의도한 포트로 바인딩됐는지 확인한다.
        }
    }

    /**
     * 테스트용 서버와 실행 스레드를 소유하며 유한 대기와 명시적 정리를 제공한다.
     */
    private static final class RunningServer implements AutoCloseable {
        final Endpoint endpoint = new Endpoint(0); // 테스트별로 충돌하지 않는 임의 포트의 서버를 소유한다.
        final ExecutorService worker = Executors.newSingleThreadExecutor(); // 블로킹 serve 루프를 테스트 스레드와 분리해서 실행한다.
        final AtomicReference<Thread> thread = new AtomicReference<>(); // 서버 작업자의 스택을 다른 스레드에서 관찰할 수 있게 참조를 공유한다.
        final Future<?> task; // 서버 작업 완료와 예외를 관찰할 Future를 보관한다.

        /**
         * 임의 포트 서버를 작업자에서 시작하고 수락 대기 진입까지 확인한다.
         * @throws Exception 서버 생성·시작·대기 상태 확인·실패 후 정리에 실패한 경우
         */
        RunningServer() throws Exception {
            CountDownLatch started = new CountDownLatch(1); // 작업자가 실제 실행돼 스레드 참조를 등록했음을 알릴 신호를 만든다.
            task = worker.submit(() -> { // serve 루프를 작업자에서 실행하고 그 결과를 Future로 받는다.
                thread.set(Thread.currentThread()); // 대기 상태를 관찰할 실제 서버 실행 스레드를 기록한다.
                started.countDown(); // 스레드 참조 기록 이후에만 생성자 쪽의 시작 대기를 해제한다.
                endpoint.serve(); // 작업자 스레드에서 종료 요청이 올 때까지 수락·응답 루프를 실행한다.
                return null; // 반환값 없는 Callable 작업의 정상 완료를 나타낸다.
            });
            try { // 서버 시작과 수락 대기 확인에 실패하면 생성한 자원을 직접 정리한다.
                assertTrue(started.await(3, TimeUnit.SECONDS)); // 서버 작업자의 시작 신호를 최대 3초 기다린다.
                awaitBlockedIn("serve"); // 클라이언트 요청 전에 서버가 accept 대기에 들어갔음을 확인한다.
            } catch (Throwable failure) { // 초기화 중 assertion 실패도 포함해 생성 실패 시 자원을 정리한다.
                close(); // 생성자 실패로 호출자가 소유하지 못할 서버와 작업자를 직접 닫는다.
                throw failure; // 테스트 호출자에게 서버 시작 또는 대기 상태 확인의 실패를 전파한다.
            }
        }

        /**
         * 테스트 클라이언트가 접속할 실제 포트를 반환한다.
         * @return Endpoint에 배정된 포트
         */
        int port() {
            return endpoint.getPort(); // 서버가 실제 배정받은 포트를 클라이언트와 재바인딩 검사에 제공한다.
        }

        /**
         * 최대 3초 동안 서버 스택을 관찰하여 지정 메서드의 소켓 대기 진입을 확인한다.
         * <p>sun.nio.ch 스택 이름에 의존하므로 JDK 구현이 바뀌면 조정이 필요할 수 있다.</p>
         * @param method 대기 중이어야 하는 Endpoint 메서드 이름
         * @throws Exception 대기 전에 끝난 서버 작업에서 실패가 발생한 경우
         * @throws AssertionError 서버가 조기 종료하거나 제한 시간 안에 소켓 대기에 진입하지 않은 경우
         */
        void awaitBlockedIn(String method) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); // 시각 변경에 영향받지 않는 단조 시간으로 3초 관찰 기한을 정한다.
            while (System.nanoTime() < deadline) { // 유한한 관찰 기한 동안만 서버 대기 상태를 확인한다.
                if (task.isDone()) { // 소켓 대기에 도달하기 전에 서버가 끝났는지 먼저 검사한다.
                    task.get(1, TimeUnit.SECONDS); // 끝난 작업의 실행 예외를 테스트 스레드에 전달한다.
                    fail("서버가 대기 전에 종료됨"); // 예외 없이 끝났더라도 필요한 대기 상태에 도달하지 못했으면 실패시킨다.
                }
                StackTraceElement[] stack = thread.get().getStackTrace(); // 시작 신호로 공개된 서버 스레드의 현재 호출 스택을 읽는다.
                boolean inEndpoint = Arrays.stream(stack).anyMatch(frame -> // 지정된 Endpoint 메서드가 호출 스택에 있는지 조사한다.
                        frame.getClassName().equals(Endpoint.class.getName()) && frame.getMethodName().equals(method)); // 다른 클래스의 동명 메서드를 제외하고 클래스와 메서드 이름을 함께 비교한다.
                boolean inSocket = Arrays.stream(stack).anyMatch(frame -> // 실제 소켓 I/O 경로도 스택에 있는지 함께 조사한다.
                        frame.getClassName().startsWith("sun.nio.ch.") // 이 테스트 환경에서 사용하는 JDK 소켓 구현의 프레임만 선택한다.
                                && (frame.getMethodName().contains("accept") // 소켓 구현의 수락 대기 경로를 후보로 인정한다.
                                || frame.getMethodName().contains("read") // 소문자 read를 포함하는 읽기 경로도 후보로 인정한다.
                                || frame.getMethodName().contains("Read"))); // 대문자 Read를 포함하는 JDK 내부 읽기 메서드도 후보로 인정한다.
                if (inEndpoint && inSocket) { // 지정 메서드와 소켓 I/O 프레임이 같은 스냅샷에 있는지 확인한다.
                    return; // 관찰하려던 서버 대기 상태를 확인했으므로 호출자를 진행시킨다.
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1)); // 재관찰 전 1ms 대기를 요청해 바쁜 반복을 줄이며 실제 재개 시점은 스케줄러에 달려 있다.
            }
            fail("소켓 대기 상태에 도달하지 못함: " + method); // 3초 내에 상태를 확인하지 못하면 무한 대기 대신 실패시킨다.
        }

        /**
         * Endpoint를 닫고 실행 작업이 끝날 때까지 최대 5초 기다린다.
         * @throws Exception 소켓 정리·작업 종료 대기·서버 작업 실행에 실패한 경우
         */
        void stop() throws Exception {
            endpoint.close(); // Endpoint가 소유한 수신 소켓과 현재 연결을 정리한다.
            task.get(5, TimeUnit.SECONDS); // close 호출과 별도로 serve 작업이 최대 5초 안에 끝나는지 확인한다.
        }

        /**
         * 서버 종료를 요청하고 작업자도 정리하여 테스트 밖으로 스레드가 남지 않게 한다.
         * @throws Exception 서버 종료나 작업자 종료 대기에 실패한 경우
         */
        @Override
        public void close() throws Exception {
            try { // 서버 종료 확인이 실패해도 finally에서 작업자를 정리한다.
                stop(); // 서버 소켓을 정리하고 실행 작업의 완료를 확인한다.
            } finally { // 앞선 동작의 성공 여부와 관계없이 소유한 자원을 정리한다.
                worker.shutdownNow(); // 서버 종료 확인에 실패했더라도 작업자 종료를 요청한다.
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); // 작업자 스레드가 남지 않도록 최대 5초의 실제 종료 대기를 확인한다.
            }
        }
    }
}
