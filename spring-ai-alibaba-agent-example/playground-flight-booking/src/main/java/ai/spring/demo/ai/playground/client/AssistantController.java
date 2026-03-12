package ai.spring.demo.ai.playground.client;

import ai.spring.demo.ai.playground.services.CustomerSupportAssistant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

	private final CustomerSupportAssistant agent;

	public AssistantController(CustomerSupportAssistant agent) {
		this.agent = agent;
	}

	@RequestMapping(path="/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chat(@RequestParam(name = "chatId") String chatId,
							 @RequestParam(name = "userMessage") String userMessage) {
		return agent.chat(chatId, userMessage);
	}

	@GetMapping(path = "/eval", produces = MediaType.APPLICATION_JSON_VALUE)
	public CustomerSupportAssistant.EvalResponse eval(
			@RequestParam(name = "chatId") String chatId,
			@RequestParam(name = "userMessage") String userMessage,
			@RequestParam(name = "topK", defaultValue = "5") int topK) {
		return agent.eval(chatId, userMessage, topK);
	}

	@GetMapping(path = "/retrieval", produces = MediaType.APPLICATION_JSON_VALUE)
	public CustomerSupportAssistant.RetrievalResponse retrieval(
			@RequestParam(name = "chatId") String chatId,
			@RequestParam(name = "userMessage") String userMessage,
			@RequestParam(name = "topK", defaultValue = "5") int topK) {
		return agent.retrieval(chatId, userMessage, topK);
	}
}
