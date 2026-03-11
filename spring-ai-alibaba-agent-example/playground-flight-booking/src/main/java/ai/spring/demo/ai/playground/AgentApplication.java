package ai.spring.demo.ai.playground;

import ai.spring.demo.ai.playground.Splitter.MyTextSplit;
import ai.spring.demo.ai.playground.chatmemory.FileBasedChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SpringBootApplication
public class AgentApplication  {

	private static final Logger logger = LoggerFactory.getLogger(AgentApplication.class);

	public static void main(String[] args) {
		new SpringApplicationBuilder(AgentApplication.class).run(args);
	}

	@Bean
	CommandLineRunner ingestTermOfServiceToVectorStore(VectorStore vectorStore) {

		return args -> {
			List<String> splitList = Arrays.asList("。", "！", "？", System.lineSeparator());
			MyTextSplit splitter = new MyTextSplit(300, 100, 5, 10000, true, splitList);

			PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
			Resource[] resources = resolver.getResources("classpath:rag/*.md");

			if (resources.length == 0) {
				logger.warn("No markdown knowledge files found under classpath:rag/*.md");
				return;
			}

			for (Resource resource : resources) {
				String source = resource.getFilename();
				String docType = inferDocType(source);
				List<Document> rawDocuments = new TextReader(resource).read();
				List<Document> chunks = splitter.transform(rawDocuments);

				for (int i = 0; i < chunks.size(); i++) {
					Document chunk = chunks.get(i);
					Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
					metadata.put("source", source);
					metadata.put("doc_type", docType);
					metadata.put("chunk_id", source + "#" + i);
					chunks.set(i, new Document(chunk.getId(), chunk.getText(), metadata));
				}

				vectorStore.write(chunks);
				logger.info("Ingested knowledge file: {} (doc_type={}) with {} chunks", source, docType, chunks.size());
			}

			vectorStore.similaritySearch("退票手续费怎么收").forEach(doc -> {
				logger.info("Sample retrieval: source={}, chunk_id={}",
						doc.getMetadata().get("source"), doc.getMetadata().get("chunk_id"));
			});
		};
	}

	private static String inferDocType(String source) {
		if (!StringUtils.hasText(source)) {
			return "general";
		}
		String lower = source.toLowerCase(Locale.ROOT);
		if (lower.contains("refund") || source.contains("退票")) {
			return "refund";
		}
		if (lower.contains("reschedule") || source.contains("改签")) {
			return "reschedule";
		}
		if (lower.contains("baggage") || source.contains("行李")) {
			return "baggage";
		}
		if (lower.contains("checkin") || source.contains("值机")) {
			return "checkin";
		}
		if (lower.contains("special") || source.contains("特殊")) {
			return "special_passenger";
		}
		return "general";
	}

	@Bean
	public VectorStore vectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	public ChatMemory chatMemory() {
		return new FileBasedChatMemory("chat-memory");
	}

	@Bean
	@ConditionalOnMissingBean
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
