package simpletomcat.coyote.http11;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import simpletomcat.coyote.*;

/** HTTP 스트림 처리기다. 스트림 소유권은 호출자에게 있다. */
public final class Http11Processor {
    private final Adapter adapter;

    public Http11Processor(Adapter adapter) { this.adapter = Objects.requireNonNull(adapter); }

    /** 요청 한 건을 처리한다. 입력·출력 실패는 전파하고 어떤 스트림도 닫지 않는다. */
    public void process(InputStream input, OutputStream output) throws IOException {
        ParsingContext context = new ParsingContext();
        Response response;
        try {
            Request request = parse(input, context);
            response = new Response();
            try {
                adapter.service(request, response);
            } catch (Exception failure) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG, "Adapter failed", failure);
                response = error(500); // 앱이 작성 중이던 헤더와 본문을 함께 버린다.
            }
        } catch (InvalidRequest failure) {
            response = error(failure.status);
        } catch (EOFException incomplete) {
            return;
        }
        // 출력은 오류 변환 범위 밖이다. 일부 전송 뒤 실패해도 두 번째 응답을 쓰지 않는다.
        write(output, context, response);
    }

    private Request parse(InputStream input, ParsingContext context) throws IOException, InvalidRequest {
        HeaderReader reader = new HeaderReader(input);
        String line = reader.readLine();
        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !Response.isToken(parts[0]) || !parts[2].matches("HTTP/[0-9]\\.[0-9]")) bad();
        String method = parts[0];
        String target = parts[1];
        String protocol = parts[2];
        context.head = method.equals("HEAD"); // 이후 Host·본문 오류에도 메서드 의미를 보존한다.
        if (protocol.equals("HTTP/1.0") || protocol.equals("HTTP/1.1")) context.protocol = protocol;
        if (!(target.startsWith("/") || method.equals("OPTIONS") && target.equals("*"))
                || target.chars().anyMatch(c -> c <= 32 || c >= 127 || "\"#<>\\^`{|}".indexOf(c) >= 0)) bad();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (!(line = reader.readLine()).isEmpty()) {
            if (++count > 100) bad();
            int colon = line.indexOf(':');
            if (colon < 1 || !Response.isToken(line.substring(0, colon))) bad();
            String value = line.substring(colon + 1);
            if (value.chars().anyMatch(c -> c < 32 && c != 9 || c == 127)) bad();
            headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(value.strip());
        }
        if (!protocol.equals("HTTP/1.0") && !protocol.equals("HTTP/1.1")) throw new InvalidRequest(505);
        List<String> hosts = headers.getOrDefault("host", List.of());
        if (hosts.size() > 1 || protocol.equals("HTTP/1.1") && hosts.isEmpty()) bad();
        if (!hosts.isEmpty()) validateHost(hosts.getFirst());
        validateFraming(headers);
        int query = target.indexOf('?');
        return new Request(method, query < 0 ? target : target.substring(0, query),
                query < 0 ? null : target.substring(query + 1), protocol, headers);
    }

    private void validateHost(String host) throws InvalidRequest {
        try {
            URI authority = URI.create("http://" + host);
            if (host.isEmpty() || authority.getHost() == null || authority.getRawUserInfo() != null
                    || !host.equals(authority.getRawAuthority()) || authority.getPort() > 65535 || host.endsWith(":")) bad();
        } catch (IllegalArgumentException malformed) {
            bad();
        }
    }

    private void validateFraming(Map<String, List<String>> headers) throws InvalidRequest {
        List<String> lengths = headers.getOrDefault("content-length", List.of());
        List<String> encodings = headers.getOrDefault("transfer-encoding", List.of());
        if (lengths.size() > 1 || !lengths.isEmpty() && !encodings.isEmpty()) bad();
        long length = 0;
        if (!lengths.isEmpty()) {
            String text = lengths.getFirst();
            if (!text.matches("[0-9]+")) bad();
            try { length = Long.parseLong(text); } catch (NumberFormatException malformed) { bad(); }
        }
        if (!encodings.isEmpty()) {
            new CodingReader(String.join(",", encodings)).validate();
            throw new InvalidRequest(501);
        }
        if (length > 0) throw new InvalidRequest(501);
    }

    private static void bad() throws InvalidRequest { throw new InvalidRequest(400); }
    private static Response error(int status) {
        Response response = new Response();
        response.setStatus(status);
        response.setBody(status + " " + reason(status) + "\n");
        return response;
    }

    private void write(OutputStream output, ParsingContext context, Response response) throws IOException {
        byte[] body = response.getBody().getBytes(StandardCharsets.UTF_8);
        int status = response.getStatus();
        boolean noBody = status == 204 || status == 205 || status == 304;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.getHeaders());
        headers.remove("Transfer-Encoding");
        headers.remove("Trailer");
        headers.remove("Content-Length");
        headers.putIfAbsent("Content-Type", "text/plain; charset=UTF-8");
        if (!noBody) headers.put("Content-Length", Integer.toString(body.length));
        else if (status == 205) headers.put("Content-Length", "0");
        headers.put("Connection", "close");
        StringBuilder wire = new StringBuilder(context.protocol).append(' ').append(status).append(' ').append(reason(status)).append("\r\n");
        headers.forEach((name, value) -> wire.append(name).append(": ").append(value).append("\r\n"));
        wire.append("\r\n");
        output.write(wire.toString().getBytes(StandardCharsets.US_ASCII));
        if (!noBody && !context.head) output.write(body);
        output.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 505 -> "HTTP Version Not Supported";
            default -> "";
        };
    }

    private static final class InvalidRequest extends Exception {
        final int status;
        InvalidRequest(int status) { this.status = status; }
    }

    private static final class ParsingContext {
        boolean head;
        String protocol = "HTTP/1.1";
    }

    /** 재귀나 반복 정규식 없이 전송 코딩·매개변수·따옴표를 한 번씩 검사한다. */
    private static final class CodingReader {
        private final String value;
        private int pos;
        CodingReader(String value) { this.value = value; }
        void validate() throws InvalidRequest {
            while (true) {
                skipWhitespace();
                boolean chunked = token().equalsIgnoreCase("chunked");
                skipWhitespace();
                while (take(';')) {
                    if (chunked) bad();
                    skipWhitespace();
                    token();
                    skipWhitespace();
                    if (!take('=')) bad();
                    skipWhitespace();
                    if (take('"')) quoted();
                    else token();
                    skipWhitespace();
                }
                if (pos == value.length()) return;
                if (chunked || !take(',')) bad();
                // 다음 반복의 token()이 빈 요소·끝의 쉼표를 거부한다.
            }
        }
        private String token() throws InvalidRequest {
            int start = pos;
            while (pos < value.length() && Response.isToken(String.valueOf(value.charAt(pos)))) pos++;
            if (pos == start) bad();
            return value.substring(start, pos);
        }
        private void quoted() throws InvalidRequest {
            while (pos < value.length()) {
                char c = value.charAt(pos++);
                if (c == '"') return;
                if (c == '\\') {
                    if (pos == value.length()) bad();
                    c = value.charAt(pos++);
                }
                if (c < 32 && c != '\t' || c == 127) bad();
            }
            bad();
        }
        private void skipWhitespace() {
            while (pos < value.length() && (value.charAt(pos) == ' ' || value.charAt(pos) == '\t')) pos++;
        }
        private boolean take(char c) {
            if (pos == value.length() || value.charAt(pos) != c) return false;
            pos++;
            return true;
        }
    }

    /** 줄 경계와 전체 크기를 검사하며 본문을 미리 읽지 않는다. */
    private static final class HeaderReader {
        private final InputStream input;
        private int bytes;
        HeaderReader(InputStream input) { this.input = input; }
        String readLine() throws IOException, InvalidRequest {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (true) {
                int value = read();
                if (value == '\r') {
                    if (read() != '\n') bad();
                    return line.toString(StandardCharsets.ISO_8859_1);
                }
                if (value == '\n') bad();
                line.write(value);
            }
        }
        private int read() throws IOException, InvalidRequest {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (++bytes > 8192) bad();
            return value;
        }
    }
}
