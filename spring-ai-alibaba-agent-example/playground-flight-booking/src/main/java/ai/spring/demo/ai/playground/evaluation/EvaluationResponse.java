package ai.spring.demo.ai.playground.evaluation;

import java.util.Map;
import java.util.Objects;

public class EvaluationResponse {
    private final boolean pass;
    private final float score;
    private final String feedback;
    private final Map<String, Object> metadata;

    public EvaluationResponse(boolean pass, float score, String feedback, Map<String, Object> metadata) {
        this.pass = pass;
        this.score = score;
        this.feedback = feedback;
        this.metadata = metadata;
    }

    public EvaluationResponse(boolean pass, String feedback, Map<String, Object> metadata) {
        this.pass = pass;
        this.score = 0.0F;
        this.feedback = feedback;
        this.metadata = metadata;
    }

    public boolean isPass() {
        return this.pass;
    }

    public float getScore() {
        return this.score;
    }

    public String getFeedback() {
        return this.feedback;
    }

    public Map<String, Object> getMetadata() {
        return this.metadata;
    }

    public String toString() {
        boolean var10000 = this.pass;
        return "EvaluationResponse{pass=" + var10000 + ", score=" + this.score + ", feedback='" + this.feedback + "', metadata=" + String.valueOf(this.metadata) + "}";
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof EvaluationResponse)) {
            return false;
        } else {
            EvaluationResponse that = (EvaluationResponse)o;
            return this.pass == that.pass && Float.compare(this.score, that.score) == 0 && Objects.equals(this.feedback, that.feedback) && Objects.equals(this.metadata, that.metadata);
        }
    }

    public int hashCode() {
        return Objects.hash(new Object[]{this.pass, this.score, this.feedback, this.metadata});
    }
}
