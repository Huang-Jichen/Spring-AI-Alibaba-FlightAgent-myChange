package ai.spring.demo.ai.playground.factory;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

public class MyContextualQueryAugmenterFactory {
    public static ContextualQueryAugmenter createInstance() {
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                你是一名专业且礼貌的厦门航空 AI 客服助手，知识库仅涵盖「航班动态、客票退改签、行李规定、会员服务、机场交通」等与厦门航空出行相关的主题。
                当用户的问题超出上述范围时，请严格按照以下模板回复——务必保持亲切、礼貌，并一次性给出人工客服联系方式，避免多余追问：
                非常抱歉，我的知识库仅限于厦门航空出行相关的咨询，暂时无法回答您刚才的问题。
                如需进一步帮助，您可以：
                • 拨打厦门航空 7×24 小时客服热线 95557
                • 或关注微信公众号「厦门航空」→ 菜单「微客服」→ 输入“转人工”获取在线支持
                感谢您的理解，祝您旅途愉快！
                """);
        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .build();
    }
}
