package simpletomcat.util.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;

/**
 * 연결마다 요청 하나를 처리하는 순차 실행 학습 서버다.
 * <p>요청 처리는 Handler에 위임하고 HTTP 정책을 알지 못한다.
 * 수신 소켓과 현재 연결은 이 객체가 소유하고, 실행 스레드는 호출자가 관리한다.</p>
 */
public final class Endpoint implements AutoCloseable {
    private final Handler handler;
    private final ServerSocket listener; // 새 TCP 연결을 기다리는 수신 소켓이다.
    private final Object lifecycle = new Object(); // 연결 등록과 종료의 실행 순서를 보호하는 공통 잠금이다.
    private final int port; // 포트 0으로 생성했을 때도 실제 배정된 포트를 보존한다.
    private volatile boolean closed; // 잠금 밖의 serve 루프에도 다른 스레드의 종료 요청을 전달한다.
    private boolean serveCalled; // lifecycle 잠금 안에서만 접근하여 serve의 중복 실행을 막는다.
    private Socket current; // lifecycle 잠금으로 보호하며 close가 취소할 현재 연결을 가리킨다.

    /**
     * 수신 소켓을 만들고 모든 로컬 인터페이스의 지정 포트에 즉시 바인딩한다.
     *
     * @param port 수신 포트(0이면 운영체제가 임의 포트를 배정한다)
     * @param handler 소켓 스트림을 빌려 처리하는 연결 처리기
     * @throws IOException 소켓 생성 또는 바인딩에 실패한 경우
     * @throws IllegalArgumentException 포트가 0~65535 범위를 벗어난 경우
     */
    public Endpoint(int port, Handler handler) throws IOException {
        this.handler = Objects.requireNonNull(handler);
        listener = new ServerSocket(); // 아직 바인딩하지 않은 소켓을 만들어 설정 실패도 직접 정리한다.
        try { // 설정과 바인딩 중 실패하면 아래 catch에서 생성한 소켓을 닫는다.
            listener.setReuseAddress(true); // 종료 후 주소 재사용이 가능하도록 바인딩 전에 옵션을 설정한다.
            listener.bind(new InetSocketAddress(port)); // wildcard 주소에 바인딩하며 0이면 임의 포트를 배정받는다.
            this.port = listener.getLocalPort(); // 요청한 값 대신 실제 바인딩된 포트를 저장한다.
        } catch (IOException | RuntimeException failure) { // 바인딩 오류와 잘못된 포트 등 실행 예외를 함께 정리한다.
            try { // 최초 실패를 보존하면서 소켓 정리를 시도한다.
                listener.close(); // 생성자가 반환하지 못해도 수신 소켓 자원이 남지 않게 한다.
            } catch (IOException cleanup) { // 정리 중 발생한 별도 오류도 잃지 않는다.
                failure.addSuppressed(cleanup); // 최초 실패의 부가 예외로 정리 실패를 기록한다.
            }
            throw failure; // 호출자가 원래 생성 실패 원인을 확인하도록 다시 던진다.
        }
    }

    /**
     * 종료 후에도 생성 시 배정받은 포트를 반환한다.
     *
     * @return 실제 바인딩 포트
     */
    public int getPort() {
        return port; // 임의 포트를 사용하는 테스트와 실행 안내에 실제 포트를 제공한다.
    }

    /**
     * 호출 스레드에서 연결을 순차 수락하고 Handler에 처리를 위임한다.
     * <p>한 번만 호출할 수 있다. 첫 호출 전에 이미 닫혔다면 바로 반환한다.
     * 연결별 I/O 오류는 다음 연결로 복구하고, 루프를 떠날 때 수신 소켓도 정리한다.</p>
     *
     * @throws IOException 종료 요청과 무관한 수락 실패 또는 최종 자원 정리 실패가 발생한 경우
     * @throws IllegalStateException 이 객체에서 serve를 이미 호출한 경우
     */
    public void serve() throws IOException {
        synchronized (lifecycle) { // 두 스레드가 동시에 실행권을 얻지 못하도록 확인과 기록을 묶는다.
            if (serveCalled) { // 이미 시작했거나 종료한 객체를 다시 실행하려는지 확인한다.
                throw new IllegalStateException("serve may be called only once"); // 기존 실행 루프를 건드리지 않고 거부한다.
            }
            serveCalled = true; // 실행권을 소비하여 이후 모든 serve 호출을 거부한다.
        }
        try { // 루프가 정상 반환하거나 실패하더라도 finally에서 서버 자원을 닫는다.
            while (!closed) { // 다른 스레드가 종료를 요청하지 않은 동안 다음 연결을 처리한다.
                Socket accepted; // 이번 반복에서 수락한 연결을 보관한다.
                try { // 수신 소켓의 실패를 개별 연결의 실패와 구별한다.
                    accepted = listener.accept(); // 잠금을 잡지 않고 새 연결이 들어올 때까지 기다린다.
                } catch (IOException failure) { // close에 의한 대기 해제도 이 경로로 들어온다.
                    if (closed) { // 종료 플래그가 있으면 의도한 취소로 처리한다.
                        return; // finally에서 정리한 뒤 serve 호출자에게 정상 반환한다.
                    }
                    throw failure; // 종료와 무관한 수락 장애는 호출자가 알 수 있게 전파한다.
                }
                try (Socket socket = accepted) { // 응답·EOF·예외·continue 어느 경로에서도 수락한 연결을 닫는다.
                    synchronized (lifecycle) { // close와 같은 잠금으로 현재 연결의 소유권을 등록한다.
                        if (closed) { // accept 반환 직후 close가 먼저 실행됐을 가능성을 다시 확인한다.
                            continue; // try-with-resources가 새 연결을 닫으며 다음 루프 조건에서 종료한다.
                        }
                        current = socket; // 등록 이후에는 close가 이 연결의 읽기·쓰기를 취소할 수 있다.
                    }
                    handler.process(socket.getInputStream(), socket.getOutputStream()); // 스트림을 빌려 주고 최종 close는 Endpoint가 수행한다.
                } catch (IOException connectionFailure) { // 연결의 읽기·쓰기·정리 실패가 수신 루프를 종료시키지 않게 한다.
                    System.getLogger(Endpoint.class.getName()).log( // 연결 오류의 원인을 진단 로그에 남긴다.
                            System.Logger.Level.DEBUG, "Connection closed during I/O", connectionFailure); // 다음 연결을 계속 받을 수 있는 오류다.
                } finally { // 연결 처리 결과와 관계없이 활성 연결 등록을 해제한다.
                    synchronized (lifecycle) { // close와의 경합 없이 등록 정보를 수정한다.
                        current = null; // 이미 처리한 연결을 이후 close가 활성 연결로 오인하지 않게 한다.
                    }
                }
            }
        } finally { // serve가 소유한 수신 소켓을 모든 종료 경로에서 정리한다.
            close(); // 외부에서 먼저 닫았더라도 반복 호출을 허용한다.
        }
    }

    /**
     * 수신 소켓과 현재 연결을 닫아 accept 및 연결 I/O 대기를 취소한다.
     * <p>반복 호출할 수 있다. serve 스레드가 끝날 때까지 기다리지는 않으므로
     * 스레드 완료 대기는 serve 호출자가 담당한다.</p>
     *
     * @throws IOException 수신 소켓 또는 현재 연결 정리에 실패한 경우
     */
    @Override
    public void close() throws IOException {
        synchronized (lifecycle) { // 수락 연결의 등록과 종료가 서로 엇갈려 연결을 놓치지 않게 한다.
            if (closed) { // 이미 종료가 요청됐는지 검사한다.
                return; // 중복 정리 없이 반환하여 반복 close를 허용한다.
            }
            closed = true; // 소켓을 닫기 전에 취소 상태를 공개해 serve가 종료 예외를 구별하게 한다.
            try { // 수신 소켓 정리 실패와 관계없이 현재 연결도 정리한다.
                listener.close(); // 새 연결 수락을 막고 accept 대기를 깨운다.
            } finally { // 수신 소켓 close가 예외를 던져도 이 블록은 실행한다.
                if (current != null) { // 등록된 활성 연결이 있을 때만 연결 정리를 수행한다.
                    current.close(); // 헤더 읽기나 응답 쓰기로 대기 중인 연결을 취소한다.
                }
            }
        }
    }

    /** 스트림은 빌려서 처리하며 종료하지 않는다. IOException은 연결별로 복구한다. */
    @FunctionalInterface
    public interface Handler {
        void process(InputStream input, OutputStream output) throws IOException;
    }
}
