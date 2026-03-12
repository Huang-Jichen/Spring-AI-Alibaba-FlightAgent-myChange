package ai.spring.demo.ai.playground.evaluation;

import java.util.Collections;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

public class RelevancyEvaluator implements Evaluator {
    private static final PromptTemplate DEFAULTPROMPTTEMPLATE = new PromptTemplate("\tYour task is to evaluate if the response for the query\n\tis in line with the context information provided.\n\n\tYou have two options to answer. Either YES or NO.\n\n\tAnswer YES, if the response for the query\n\tis in line with context information otherwise NO.\n\n\tQuery:\n\t{query}\n\n\tResponse:\n\t{response}\n\n\tContext:\n\t{context}\n\n\tAnswer:\n");
    private final ChatClient.Builder chatClientBuilder;
    private final PromptTemplate promptTemplate;

    public RelevancyEvaluator(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, (PromptTemplate)null);
    }

    private RelevancyEvaluator(ChatClient.Builder chatClientBuilder, @Nullable PromptTemplate promptTemplate) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        this.chatClientBuilder = chatClientBuilder;
        this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULTPROMPTTEMPLATE;
    }

    public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
        String response = evaluationRequest.getResponseContent();
        String context = this.doGetSupportingData(evaluationRequest);
        String userMessage = this.promptTemplate.render(Map.of("query", evaluationRequest.getUserText(), "response", response, "context", context));
        String evaluationResponse = this.chatClientBuilder.build().prompt().user(userMessage).call().content();
        boolean passing = false;
        float score = 0.0F;
        if (evaluationResponse != null && evaluationResponse.toLowerCase().contains("yes")) {
            passing = true;
            score = 1.0F;
        }

        return new EvaluationResponse(passing, score, "", Collections.emptyMap());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatClient.Builder chatClientBuilder;
        private PromptTemplate promptTemplate;

        private Builder() {
        }

        public Builder chatClientBuilder(ChatClient.Builder chatClientBuilder) {
            this.chatClientBuilder = chatClientBuilder;
            return this;
        }

        public Builder promptTemplate(PromptTemplate promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public RelevancyEvaluator build() {
            return new RelevancyEvaluator(this.chatClientBuilder, this.promptTemplate);
        }
    }
}
