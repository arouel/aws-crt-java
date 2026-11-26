package software.amazon.awssdk.crt.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Assume;
import org.junit.Test;

import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.s3.S3Client;
import software.amazon.awssdk.crt.s3.S3ClientOptions;
import software.amazon.awssdk.crt.s3.S3FinishedResponseContext;
import software.amazon.awssdk.crt.s3.S3MetaRequest;
import software.amazon.awssdk.crt.s3.S3MetaRequestOptions;
import software.amazon.awssdk.crt.s3.S3MetaRequestOptions.MetaRequestType;
import software.amazon.awssdk.crt.s3.S3MetaRequestResponseHandler;
import software.amazon.awssdk.crt.s3.S3RequestMetrics;

/**
 * Tests GET hedging against the mock server of aws-c-s3 (crt/aws-c-s3/tests/mock_s3_server). The tests that need
 * the mock server run only if the system property aws.crt.s3.mock_server gives its endpoint, for example
 * http://localhost:8080.
 */
public class S3ClientGetObjectHedgingTest extends CrtTestFixture {

    private static final String MOCK_SERVER_PROPERTY = "aws.crt.s3.mock_server";
    private static final String REGION = "us-west-2";
    private static final int AWS_ERROR_HTTP_RESPONSE_FIRST_BYTE_TIMEOUT = 2092;

    /* Both routes serve a 256 KiB object, which the client gets in 4 parts of 64 KiB. */
    private static final long PART_SIZE = 64 * 1024;
    private static final long OBJECT_SIZE = 256 * 1024;
    private static final String FAST_PATH = "/get_object_opaque_etag";
    /* The mock server answers the first attempt of part 2 of this route after 3 s. */
    private static final String SLOW_PART_PATH = "/get_object_hedging_slow_part";
    private static final long SLOW_ANSWER_MS = 3000;

    /*
     * The warmup samples are fast, so the timeout starts at its minimum of 1 s. Each success then moves it a
     * little toward the mean plus 700 ms.
     */
    private static final long MAX_DEADLINE_MS = 1000;
    private static final long MIN_DEADLINE_MS = 900;

    /*
     * The warmup needs max(10, ideal connection count) successes. A target of 1 Gbps gives the minimum of 10
     * connections. 3 downloads of 4 parts give 12 successes.
     */
    private static final double THROUGHPUT_TARGET_GBPS = 1.0;
    private static final int WARMUP_DOWNLOADS = 3;

    private static final class Download {
        final long durationMs;
        final List<S3RequestMetrics> attempts;

        Download(long durationMs, List<S3RequestMetrics> attempts) {
            this.durationMs = durationMs;
            this.attempts = attempts;
        }
    }

    @Test
    public void testGetObjectHedgingIsNotSetByDefault() {
        assertNull(new S3ClientOptions().getGetObjectHedging());
        assertEquals(Boolean.TRUE, new S3ClientOptions().withGetObjectHedging(true).getGetObjectHedging());
        assertEquals(Boolean.FALSE, new S3ClientOptions().withGetObjectHedging(false).getGetObjectHedging());
    }

    @Test
    public void testGetObjectHedgingSendsSlowPartAgain() throws Exception {
        URI endpoint = mockServerEndpoint();
        try (S3Client client = createClient(true)) {
            for (int i = 0; i < WARMUP_DOWNLOADS; i++) {
                download(client, endpoint, FAST_PATH);
            }

            Download download = download(client, endpoint, SLOW_PART_PATH);

            assertTrue("download took " + download.durationMs + " ms", download.durationMs < SLOW_ANSWER_MS);
            int numHedges = 0;
            int numAttemptsAfterHedge = 0;
            for (S3RequestMetrics attempt : download.attempts) {
                if (attempt.getErrorCode() == AWS_ERROR_HTTP_RESPONSE_FIRST_BYTE_TIMEOUT) {
                    numHedges++;
                } else {
                    assertEquals(CRT.awsErrorName(attempt.getErrorCode()), 0, attempt.getErrorCode());
                }
                if (attempt.getRetryCount() == 0) {
                    assertDeadline(attempt.getFirstByteTimeoutMs());
                } else {
                    numAttemptsAfterHedge++;
                    assertEquals(0, attempt.getFirstByteTimeoutMs());
                }
            }
            assertEquals(1, numHedges);
            assertEquals(1, numAttemptsAfterHedge);
        }
    }

    @Test
    public void testGetObjectHedgingOff() throws Exception {
        URI endpoint = mockServerEndpoint();
        try (S3Client client = createClient(false)) {
            for (int i = 0; i < WARMUP_DOWNLOADS; i++) {
                download(client, endpoint, FAST_PATH);
            }

            Download download = download(client, endpoint, SLOW_PART_PATH);

            assertTrue("download took " + download.durationMs + " ms", download.durationMs >= SLOW_ANSWER_MS);
            for (S3RequestMetrics attempt : download.attempts) {
                assertEquals(CRT.awsErrorName(attempt.getErrorCode()), 0, attempt.getErrorCode());
                assertEquals(0, attempt.getRetryCount());
                assertEquals(0, attempt.getFirstByteTimeoutMs());
            }
        }
    }

    private URI mockServerEndpoint() {
        skipIfAndroid();
        String endpoint = System.getProperty(MOCK_SERVER_PROPERTY);
        Assume.assumeTrue(MOCK_SERVER_PROPERTY + " is not set", endpoint != null);
        return URI.create(endpoint);
    }

    private static void assertDeadline(long firstByteTimeoutMs) {
        assertTrue("first-byte timeout is " + firstByteTimeoutMs + " ms",
                firstByteTimeoutMs >= MIN_DEADLINE_MS && firstByteTimeoutMs <= MAX_DEADLINE_MS);
    }

    private static S3Client createClient(boolean getObjectHedging) {
        try (EventLoopGroup elg = new EventLoopGroup(0, 1);
                HostResolver hostResolver = new HostResolver(elg);
                ClientBootstrap clientBootstrap = new ClientBootstrap(elg, hostResolver);
                StaticCredentialsProvider credentialsProvider = new StaticCredentialsProvider.StaticCredentialsProviderBuilder()
                        .withAccessKeyId("AKIDEXAMPLE".getBytes(StandardCharsets.UTF_8))
                        .withSecretAccessKey("wJalrXUtnFEMI".getBytes(StandardCharsets.UTF_8))
                        .build()) {
            S3ClientOptions options = new S3ClientOptions()
                    .withRegion(REGION)
                    .withClientBootstrap(clientBootstrap)
                    .withCredentialsProvider(credentialsProvider)
                    .withPartSize(PART_SIZE)
                    .withThroughputTargetGbps(THROUGHPUT_TARGET_GBPS)
                    .withGetObjectHedging(getObjectHedging);
            return new S3Client(options);
        }
    }

    private static Download download(S3Client client, URI endpoint, String path) throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicLong numBodyBytes = new AtomicLong();
        List<S3RequestMetrics> attempts = Collections.synchronizedList(new ArrayList<>());

        S3MetaRequestResponseHandler responseHandler = new S3MetaRequestResponseHandler() {
            @Override
            public int onResponseBody(ByteBuffer bodyBytesIn, long objectRangeStart, long objectRangeEnd) {
                numBodyBytes.addAndGet(bodyBytesIn.remaining());
                return 0;
            }

            @Override
            public void onFinished(S3FinishedResponseContext context) {
                if (context.getErrorCode() != 0) {
                    finished.completeExceptionally(new RuntimeException(String.format(
                            "GET %s failed with %s, response status %d", path,
                            CRT.awsErrorName(context.getErrorCode()), context.getResponseStatus())));
                    return;
                }
                finished.complete(null);
            }

            @Override
            public void onTelemetry(S3RequestMetrics metrics) {
                attempts.add(metrics);
            }
        };

        HttpHeader[] headers = {
                new HttpHeader("Host", endpoint.getAuthority()),
                // The mock server counts the attempts of each meta request with this header.
                new HttpHeader("x-mock-request-id", UUID.randomUUID().toString()),
        };
        S3MetaRequestOptions metaRequestOptions = new S3MetaRequestOptions()
                .withMetaRequestType(MetaRequestType.GET_OBJECT)
                .withHttpRequest(new HttpRequest("GET", path, headers, null))
                .withEndpoint(endpoint)
                .withResponseHandler(responseHandler);

        long startNs = System.nanoTime();
        try (S3MetaRequest metaRequest = client.makeMetaRequest(metaRequestOptions)) {
            finished.get(60, TimeUnit.SECONDS);
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertEquals(OBJECT_SIZE, numBodyBytes.get());
        synchronized (attempts) {
            return new Download(durationMs, new ArrayList<>(attempts));
        }
    }
}
