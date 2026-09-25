package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 웹훅 발송기(`Q210`) — DB 없이 로컬 서버로 잰다.
 *
 * <p>여기서 지키는 것 둘이다 — <b>IDN 주소도 검사한 주소에 못박혀 닿고</b>, <b>머리를 조금씩 흘리는 서버도 한 건의 상한에서
 * 끊겨 「타임아웃」으로 적힌다</b>. 둘 다 틀려도 보통 주소의 발송은 멀쩡해 보인다(마무리 53차 독립 리뷰).
 */
@DisplayName("웹훅 발송기")
class WebhookSenderTest {

    /**
     * httpcore5 는 {@code xn--} 이름을 유니코드로 바꿔 이름 풀이에 넘긴다. 검사한 URI 는 퓨니코드라, 글자로 견주면 IDN 엔드포인트가
     * 등록은 되고 발송은 매번 「검사 안 된 이름」으로 실패한다. {@code xn--bcher-kva.test}(bücher.test)는 예약된 이름이라 시스템
     * DNS 로 안 풀린다 — 로컬 서버가 받았으면 못박은 주소로 간 것이다.
     */
    @Test
    @DisplayName("IDN 주소도 검사한 주소로 보낸다")
    void internationalizedNameIsPinned() throws IOException {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        WebhookSender sender = new WebhookSender(Duration.ofSeconds(2), Duration.ofSeconds(2));
        try {
            WebhookUrlPolicy.Target target = new WebhookUrlPolicy.Target(
                    URI.create("http://xn--bcher-kva.test:" + server.getAddress().getPort() + "/hook"),
                    List.of(InetAddress.getLoopbackAddress()));

            WebhookSender.Result result = sender.send(target, new byte[] {1}, "1", 0, "{}");

            assertThat(result.statusCode()).as("오류: %s", result.error()).isEqualTo(204);
            assertThat(received).hasValue(1);
        } finally {
            sender.close();
            server.stop(0);
        }
    }

    /**
     * 머리의 줄을 소켓 타임아웃보다 짧은 간격으로 계속 흘리는 서버. Apache 의 응답 타임아웃은 읽기 사이의 무활동이라 이것을 못 끊는다 —
     * 한 건의 상한(연결 + 요청)이 끊어야 한다. 끊긴 읽기는 {@code SocketException} 을 던지는데 결과는 「타임아웃」이어야 한다.
     */
    @Test
    @DisplayName("머리를 흘리는 서버도 한 건의 상한에서 끊기고 타임아웃으로 적힌다")
    void headerTricklingServerHitsTheCap() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread trickler = new Thread(() -> trickle(listener), "header-trickler");
            trickler.setDaemon(true);
            trickler.start();
            // 소켓 타임아웃 300ms 보다 줄 간격(100ms)이 짧다. 상한은 200 + 300 = 500ms 다.
            WebhookSender sender = new WebhookSender(Duration.ofMillis(200), Duration.ofMillis(300));
            try {
                WebhookUrlPolicy.Target target = new WebhookUrlPolicy.Target(
                        URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/hook"),
                        List.of(InetAddress.getLoopbackAddress()));
                long started = System.nanoTime();

                WebhookSender.Result result = sender.send(target, new byte[] {1}, "1", 0, "{}");

                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(result.error()).isEqualTo("타임아웃");
            } finally {
                sender.close();
            }
        }
    }

    /** 요청을 받고 상태 줄 뒤에 머리 줄을 100ms 마다 하나씩 3초 동안 보낸다 — 빈 줄(머리의 끝)은 안 보낸다 */
    private static void trickle(ServerSocket listener) {
        try (Socket socket = listener.accept()) {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            in.read(buffer);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < 30; i++) {
                Thread.sleep(100);
                out.write(("trickle-" + i + ": 1\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // 발송기가 끊으면 쓰기가 실패한다 — 그것이 기대한 끝이다
        }
    }
}
