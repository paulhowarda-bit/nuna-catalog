package fidice.catalog.core.commit;

import static org.assertj.core.api.Assertions.assertThat;

import fidice.catalog.store.IdempotencyResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepliesTest {

    private static IdempotencyResult result(int status, String body) {
        return new IdempotencyResult("k", "alice", "hash", status, body, Instant.EPOCH);
    }

    @Test
    void successRoundTrips() {
        UUID id = UUID.randomUUID();
        String body = Replies.success(id, "s3://lake/warehouse/t/metadata/00002-x.metadata.json", "ab12");

        assertThat(Replies.decode(result(200, body)))
                .isEqualTo(new Replies.Success(id, "s3://lake/warehouse/t/metadata/00002-x.metadata.json", "ab12"));
    }

    @Test
    void failureRoundTripsAMessageWithLineBreaks() {
        String body = Replies.failure("CommitFailedException", "Requirement failed:\nline two");

        assertThat(Replies.decode(result(409, body)))
                .isEqualTo(new Replies.Failure(409, "CommitFailedException", "Requirement failed:\nline two"));
    }

    @Test
    void failureWithAnEmptyMessage() {
        assertThat(Replies.decode(result(400, Replies.failure("BadRequestException", ""))))
                .isEqualTo(new Replies.Failure(400, "BadRequestException", ""));
    }
}
