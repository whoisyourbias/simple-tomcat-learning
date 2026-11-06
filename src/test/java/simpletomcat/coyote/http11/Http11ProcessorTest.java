package simpletomcat.coyote.http11;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import simpletomcat.coyote.Adapter;
import static org.junit.jupiter.api.Assertions.*;

class Http11ProcessorTest {
    // 원문 URI·query·반복 헤더를 보내면 디코딩 없이 전달되고 UTF-8 바이트 길이로 응답해야 한다.
    @Test
    void passesRequestAndWritesUtf8Response() throws Exception {
        String wire = exchange("PATCH /a%20b?q=%EA%B0%80 HTTP/1.1\r\nHost: localhost\r\nX-Test: first\r\nx-test: second\r\nContent-Length: 0\r\n\r\n", (req, res) -> {
            assertEquals("PATCH", req.getMethod());
            assertEquals("/a%20b", req.getRequestURI());
            assertEquals("q=%EA%B0%80", req.getQueryString());
            assertEquals("HTTP/1.1", req.getProtocol());
            assertEquals(java.util.List.of("first", "second"), req.getHeaders("X-TEST"));
            res.setHeader("X-Reply", "yes");
            res.setBody("안녕");
        });
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(wire.contains("Content-Length: 6\r\n"));
        assertTrue(wire.contains("X-Reply: yes\r\n"));
        assertTrue(wire.endsWith("\r\n\r\n안녕"));
    }

    // 본문 없는 HTTP/1.0·1.1 유효 메서드는 GET 이외에도 Adapter에 전달되어야 한다.
    @ParameterizedTest
    @ValueSource(strings = {"GET / HTTP/1.0\r\n\r\n", "POST / HTTP/1.1\r\nHost: localhost\r\n\r\n", "OPTIONS * HTTP/1.1\r\nHost: localhost\r\n\r\n", "CUSTOM / HTTP/1.1\r\nHost: localhost\r\n\r\n"})
    void acceptsBodylessMethods(String input) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String wire = exchange(input, (req, res) -> { calls.incrementAndGet(); assertNull(req.getQueryString()); });
        assertTrue(wire.contains("200 OK\r\n"));
        assertEquals(1, calls.get());
    }

    // 잘못된 문법·Host·프레이밍은 400이며 Adapter를 호출하지 않아야 한다.
    @ParameterizedTest
    @ValueSource(strings = {"not-http\r\n\r\n", "GET / HTTP/1.1\r\n\r\n", "GET / HTTP/1.1\r\nHost:\r\n\r\n", "GET / HTTP/1.1\r\nHost: a\r\nHost: a\r\n\r\n", "GET / HTTP/1.1\r\nHost: bad host\r\n\r\n", "GET / HTTP/1.1\nHost: a\n\n", "GET  / HTTP/1.1\r\nHost: a\r\n\r\n", "GET / HTTP/one\r\nHost: a\r\n\r\n", "GET /#x HTTP/1.1\r\nHost: a\r\n\r\n", "GET / HTTP/1.1\r\nHost: a\r\nBad Name: x\r\n\r\n", "GET / HTTP/1.1\r\nHost: a\r\n folded\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 9223372036854775808\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked, gzip\r\n\r\n", "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: ,\r\n\r\n"})
    void rejectsMalformedWithoutAdapter(String input) throws Exception {
        assertTrue(exchange(input, (req, res) -> fail("잘못된 요청의 Adapter 호출")).startsWith("HTTP/1.1 400 Bad Request\r\n"));
    }

    // 유효하지만 미지원 HTTP 버전은 505이며 앱을 호출하지 않아야 한다.
    @Test
    void rejectsUnsupportedVersion() throws Exception {
        assertTrue(exchange("GET / HTTP/2.0\r\nHost: a\r\n\r\n", (q, s) -> fail()).contains("505 HTTP Version Not Supported"));
    }

    // 유효한 본문 프레이밍은 본문을 기다리거나 앱을 호출하지 않고 501이어야 한다.
    @ParameterizedTest
    @ValueSource(strings = {"Content-Length: 1", "Transfer-Encoding: chunked", "Transfer-Encoding: gzip, chunked"})
    void rejectsUnsupportedBody(String header) throws Exception {
        assertTrue(exchange("POST / HTTP/1.1\r\nHost: a\r\n" + header + "\r\n\r\n", (q, s) -> fail()).contains("501 Not Implemented"));
    }

    // 제한 안의 1000개 전송 코딩 매개변수는 스택 오류 없이 501로 거부되어야 한다.
    @Test
    void handlesManyTransferParametersWithoutRecursion() throws Exception {
        String wire = "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip" + ";a=b".repeat(1000) + "\r\n\r\n";
        assertTrue(exchange(wire, (q, s) -> fail()).contains("501 Not Implemented"));
    }

    // 따옴표 안 쉼표·이스케이프는 코딩 매개변수로 허용하고 미완성·빈 요소는 400이어야 한다.
    @Test
    void validatesTransferCodingParameters() throws Exception {
        for (String coding : new String[] {"gzip ; a=\"x,y\", chunked", "gzip;a=\"x\\\"y\"", "gzip; a = b"}) {
            assertTrue(exchange("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: " + coding + "\r\n\r\n", (q, s) -> fail()).contains("501 Not Implemented"));
        }
        for (String coding : new String[] {"gzip;a=\"unfinished", "gzip; a", "gzip,", "gzip,,chunked", "chunked;a=b", "chunked,chunked"}) {
            assertTrue(exchange("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: " + coding + "\r\n\r\n", (q, s) -> fail()).contains("400 Bad Request"));
        }
    }

    // 입력·flush IOException은 오류 응답 재시도 없이 전파되어야 한다.
    @Test
    void propagatesTransportFailures() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream brokenInput = new InputStream() { public int read() throws IOException { throw new IOException("read failed"); } };
        Http11Processor processor = new Http11Processor((q, s) -> {});
        assertThrows(IOException.class, () -> processor.process(brokenInput, output));
        assertEquals(0, output.size());
        AtomicInteger flushes = new AtomicInteger();
        ByteArrayOutputStream brokenFlush = new ByteArrayOutputStream() {
            public void flush() throws IOException { flushes.incrementAndGet(); throw new IOException("flush failed"); }
        };
        assertThrows(IOException.class, () -> processor.process(input(valid()), brokenFlush));
        assertEquals(1, flushes.get());
        assertEquals(1, brokenFlush.toString(StandardCharsets.UTF_8).split("HTTP/1.1", -1).length - 1);
    }

    // 헤더 전체 크기나 개수 초과는 400이며 정확한 크기 경계는 허용해야 한다.
    @Test
    void enforcesHeaderBounds() throws Exception {
        String prefix = "GET / HTTP/1.1\r\nHost: a\r\nX: ";
        String atLimit = prefix + "a".repeat(8192 - prefix.length() - 4) + "\r\n\r\n";
        assertTrue(exchange(atLimit, (q, s) -> {}).contains("200 OK"));
        assertTrue(exchange(atLimit.replace("X: ", "X: a"), (q, s) -> fail()).contains("400 Bad Request"));
        assertTrue(exchange("GET / HTTP/1.1\r\nHost: a\r\n" + "X: a\r\n".repeat(99) + "\r\n", (q, s) -> {}).contains("200 OK"));
        assertTrue(exchange("GET / HTTP/1.1\r\nHost: a\r\n" + "X: a\r\n".repeat(100) + "\r\n", (q, s) -> fail()).contains("400 Bad Request"));
    }

    // HEAD는 GET 표현의 길이만 보내고 실제 본문은 보내지 않아야 한다.
    @Test
    void suppressesHeadBody() throws Exception {
        String wire = exchange("HEAD / HTTP/1.1\r\nHost: a\r\n\r\n", (q, s) -> s.setBody("안녕"));
        assertTrue(wire.contains("Content-Length: 6\r\n"));
        assertTrue(wire.endsWith("\r\n\r\n"));
    }

    // HEAD의 Host 오류·본문 미지원 응답도 오류 표현의 길이만 보내고 본문은 없어야 한다.
    @ParameterizedTest
    @ValueSource(strings = {"HEAD / HTTP/1.1\r\n\r\n", "HEAD / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\n"})
    void suppressesHeadErrorBody(String input) throws Exception {
        String wire = exchange(input, (q, s) -> fail());
        assertTrue(wire.contains("400 Bad Request") || wire.contains("501 Not Implemented"));
        assertTrue(wire.contains("Content-Length:"));
        assertTrue(wire.endsWith("\r\n\r\n"));
    }

    // 204·205·304는 앱 본문과 잘못 지정한 프레이밍을 전송하지 않아야 한다.
    @ParameterizedTest
    @ValueSource(ints = {204, 205, 304})
    void suppressesStatusBody(int status) throws Exception {
        String wire = exchange(valid(), (q, s) -> { s.setStatus(status); s.setBody("hidden"); s.setHeader("Content-Length", "99"); s.setHeader("Transfer-Encoding", "chunked"); });
        assertTrue(wire.startsWith("HTTP/1.1 " + status + " "));
        assertTrue(wire.endsWith("\r\n\r\n"));
        assertFalse(wire.contains("Transfer-Encoding:"));
        assertEquals(status == 205, wire.contains("Content-Length: 0\r\n"));
        if (status != 205) assertFalse(wire.contains("Content-Length:"));
    }

    // 앱이 일부 응답을 만든 뒤 예외가 나면 내용·헤더를 버린 500을 보내고 다음 요청은 정상 처리해야 한다.
    @Test
    void isolatesAdapterFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Http11Processor processor = new Http11Processor((q, s) -> {
            if (calls.incrementAndGet() == 1) { s.setHeader("X-Leak", "secret"); s.setBody("secret"); throw new IOException("app failure"); }
            s.setBody("recovered");
        });
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        processor.process(input(valid()), first);
        assertTrue(first.toString(StandardCharsets.UTF_8).contains("500 Internal Server Error"));
        assertFalse(first.toString(StandardCharsets.UTF_8).contains("secret"));
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        processor.process(input(valid()), second);
        assertTrue(second.toString(StandardCharsets.UTF_8).endsWith("recovered"));
    }

    // 출력 중 IOException이 나면 500 재전송 없이 예외를 호출자에게 전달해야 한다.
    @Test
    void neverRetriesFailedOutput() {
        AtomicInteger writes = new AtomicInteger();
        OutputStream broken = new OutputStream() { public void write(int b) throws IOException { writes.incrementAndGet(); throw new IOException("broken"); } };
        assertThrows(IOException.class, () -> new Http11Processor((q, s) -> {}).process(input(valid()), broken));
        assertEquals(1, writes.get());
    }

    // 완성 전 EOF에는 무응답이며 Processor가 호출자 소유 스트림을 닫지 않아야 한다.
    @Test
    void preservesStreamOwnershipAndIncompleteEof() throws Exception {
        for (String wire : new String[] {"", "GET / HTTP/1.1\r\nHost: a\r\n", valid()}) {
            AtomicInteger closes = new AtomicInteger();
            InputStream in = new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)) { public void close() { closes.incrementAndGet(); } };
            ByteArrayOutputStream out = new ByteArrayOutputStream() { public void close() { closes.incrementAndGet(); } };
            new Http11Processor((q, s) -> {}).process(in, out);
            assertEquals(0, closes.get());
            assertEquals(wire.equals(valid()), out.size() > 0);
        }
    }

    // 앱의 CRLF 헤더 주입이나 중간 1xx 상태는 전송 전에 거부되어 단일 500이 되어야 한다.
    @Test
    void rejectsInvalidApplicationResponse() throws Exception {
        assertTrue(exchange(valid(), (q, s) -> s.setHeader("X", "ok\r\nInjected: yes")).contains("500 Internal Server Error"));
        assertTrue(exchange(valid(), (q, s) -> s.setStatus(100)).contains("500 Internal Server Error"));
    }

    private static String valid() { return "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"; }
    private static InputStream input(String wire) { return new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)); }
    private static String exchange(String wire, Adapter adapter) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Http11Processor(adapter).process(input(wire), out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
