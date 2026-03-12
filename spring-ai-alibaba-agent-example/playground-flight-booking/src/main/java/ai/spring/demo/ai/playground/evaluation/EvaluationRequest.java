package ai.spring.demo.ai.playground.evaluation;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;

public class EvaluationRequest {
    private final String userText;
    private final List<Document> dataList;
    private final String responseContent;

    public EvaluationRequest(String userText, String responseContent) {
        this(userText, Collections.emptyList(), responseContent);
    }

    public EvaluationRequest(List<Document> dataList, String responseContent) {
        this("", dataList, responseContent);
    }

    public EvaluationRequest(String userText, List<Document> dataList, String responseContent) {
        this.userText = userText;
        this.dataList = dataList;
        this.responseContent = responseContent;
    }

    public String getUserText() {
        return this.userText;
    }

    public List<Document> getDataList() {
        return this.dataList;
    }

    public String getResponseContent() {
        return this.responseContent;
    }

    public String toString() {
        String var10000 = this.userText;
        return "EvaluationRequest{userText='" + var10000 + "', dataList=" + String.valueOf(this.dataList) + ", chatResponse=" + this.responseContent + "}";
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof EvaluationRequest)) {
            return false;
        } else {
            EvaluationRequest that = (EvaluationRequest)o;
            return Objects.equals(this.userText, that.userText) && Objects.equals(this.dataList, that.dataList) && Objects.equals(this.responseContent, that.responseContent);
        }
    }

    public int hashCode() {
        return Objects.hash(new Object[]{this.userText, this.dataList, this.responseContent});
    }
}