/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.spring.demo.ai.playground.services;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import ai.spring.demo.ai.playground.factory.MyContextualQueryAugmenterFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import static org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor.TOP_K;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * * @author Christian Tzolov
 * 模拟的是一个航空公司 Funnair 的客户支持助手，具备：
 * 自然语言交互（ChatClient）
 * 记忆能力（ChatMemory）
 * 知识检索（RAG via VectorStore）
 * 函数调用（Function Calling）
 */
@Service
public class CustomerSupportAssistant {

	private final ChatClient chatClient;
	private final VectorStore vectorStore;

	public CustomerSupportAssistant(ChatClient.Builder modelBuilder, VectorStore vectorStore, ChatMemory chatMemory) {

		this.vectorStore = vectorStore;
		// @formatter:off
		this.chatClient = modelBuilder
				.defaultSystem("""
						您是“Funnair”航空公司的客户聊天支持代理。请以友好、乐于助人且愉快的方式来回复。
						您正在通过在线聊天系统与客户互动。
						您能够支持已有机票的预订详情查询、机票日期改签、机票预订取消等操作，其余功能将在后续版本中添加，如果用户问的问题不支持请告知详情。
						在提供有关机票预订详情查询、机票日期改签、机票预订取消等操作之前，您必须始终从用户处获取以下信息：预订号、客户姓名。
						在询问用户之前，请检查消息历史记录以获取预订号、客户姓名等信息，尽量避免重复询问给用户造成困扰。
						在更改预订之前，您必须确保条款允许这样做。
						如果更改需要收费，您必须在继续之前征得用户同意。
						使用提供的功能获取预订详细信息、更改预订和取消预订。
						如果需要，您可以调用相应函数辅助完成。
						请讲中文。

						今天的日期是 {current_date}.
					""")
				.defaultAdvisors(
						MessageChatMemoryAdvisor.builder(chatMemory).build(),
						RetrievalAugmentationAdvisor.builder()
								.queryTransformers(RewriteQueryTransformer.builder()
										.chatClientBuilder(modelBuilder.build().mutate())
										.build())
								.documentRetriever(VectorStoreDocumentRetriever.builder()
										.similarityThreshold(0.50)
										.vectorStore(vectorStore)
										.build())
								.queryAugmenter(MyContextualQueryAugmenterFactory.createInstance())
								.build(),
						new SimpleLoggerAdvisor()
				).defaultToolNames(
						"getBookingDetails",
						"changeBooking",
						"cancelBooking"
				).build();
		// @formatter:on
	}

	public Flux<String> chat(String chatId, String userMessageContent) {

		return this.chatClient.prompt()
				.system(s -> s.param("current_date", LocalDate.now().toString()))
				.user(userMessageContent)
				.advisors(
						a -> a.param(CONVERSATION_ID, chatId).param(TOP_K, 100))
				.stream()
				.content();
	}

	public EvalResponse eval(String chatId, String question, int topK) {
		int safeTopK = Math.max(1, Math.min(topK, 20));
		long start = System.currentTimeMillis();

		List<Document> retrieved = this.vectorStore.similaritySearch(
				SearchRequest.defaults().withQuery(question).withTopK(safeTopK).withSimilarityThreshold(0.50));

		List<RetrievedDoc> docs = java.util.stream.IntStream.range(0, retrieved.size())
				.mapToObj(i -> toRetrievedDoc(i + 1, retrieved.get(i)))
				.toList();

		String finalAnswer = this.chatClient.prompt()
				.system(s -> s.param("current_date", LocalDate.now().toString()))
				.user(question)
				.advisors(a -> a.param(CONVERSATION_ID, chatId).param(TOP_K, 100))
				.call()
				.content();

		long latency = System.currentTimeMillis() - start;
		return new EvalResponse(question, finalAnswer == null ? "" : finalAnswer, latency, docs);
	}

	private RetrievedDoc toRetrievedDoc(int rank, Document doc) {
		Map<String, Object> metadata = doc.getMetadata();
		String source = toStringOrNull(metadata.get("source"));
		String docType = toStringOrNull(metadata.get("doc_type"));
		String chunkId = toStringOrNull(metadata.get("chunk_id"));
		Double score = toDoubleOrNull(metadata.get("score"));
		return new RetrievedDoc(rank, doc.getText(), source, docType, chunkId, score);
	}

	private String toStringOrNull(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private Double toDoubleOrNull(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		}
		catch (Exception ex) {
			return null;
		}
	}

	public record RetrievedDoc(int rank, String content, String source, String doc_type, String chunk_id, Double score) {
	}

	public record EvalResponse(String question, String final_answer, long latency_ms,
							   List<RetrievedDoc> retrieved_documents) {
	}
}
