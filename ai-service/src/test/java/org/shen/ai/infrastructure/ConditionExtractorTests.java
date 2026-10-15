package org.shen.ai.infrastructure;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shen.ai.domain.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 Spring AI 响应模型验证结构边界，不发出付费请求。 */
class ConditionExtractorTests {
    private ConditionExtractor extractor(ChatModel model,String provider) {
        var models=mock(ObjectProvider.class);when(models.getIfAvailable()).thenReturn(model);
        return new ConditionExtractor(models,new ObjectMapper(),provider,"test-model");
    }
    private AiCommands.Extraction extract(ConditionExtractor model) {
        return model.extract("六个人",Map.of(),List.of(),Map.of("campuses",List.of(),"equipmentTypes",List.of()),LocalDate.of(2026,10,14));
    }
    @Test void disabledModelFailsRecoverablyInsteadOfFabricatingAReply() {
        assertThatThrownBy(() -> extract(extractor(null,"disabled"))).isInstanceOfSatisfying(AiFailure.class,e -> assertThat(e.code()).isEqualTo("AI_NOT_CONFIGURED"));
    }
    @Test void plainJsonIsConvertedButExtraToolFieldsAreRejected() {
        var model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"changes\":{\"participantCount\":6},\"evidence\":{\"participantCount\":\"六个人\"}}")))));
        assertThat(extract(extractor(model,"openai")).changes()).containsEntry("participantCount",6);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"changes\":{},\"evidence\":{},\"tool\":\"createApplication\"}")))));
        assertThatThrownBy(() -> extract(extractor(model,"openai"))).isInstanceOf(AiFailure.class);
    }
    @Test void invalidJsonAndTransportFailureHaveDifferentFailureCodes() {
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON")))));
        assertThatThrownBy(() -> extract(extractor(model,"openai"))).isInstanceOfSatisfying(AiFailure.class,e -> assertThat(e.code()).isEqualTo("AI_INVALID_OUTPUT"));
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("测试网络失败"));
        assertThatThrownBy(() -> extract(extractor(model,"openai"))).isInstanceOfSatisfying(AiFailure.class,e -> assertThat(e.code()).isEqualTo("AI_MODEL_UNAVAILABLE"));
    }
    @Test void operationEnvelopeSurvivesModelConversionAndAppliesOnlyTheIncrement() {
        var model=mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
            {"changes":{"requiredFeatures":{"operation":"ADD","items":["WHITEBOARD"]}},"evidence":{"requiredFeatures":"再加上白板"}}
            """)))));
        var today=LocalDate.of(2026,10,14);var options=Map.<String,Object>of("campuses",List.of(),"equipmentTypes",List.of());
        var old=Map.<String,Object>of("requiredFeatures",List.of("PROJECTOR"));
        var patch=extractor(model,"openai").extract("再加上白板",old,List.of(),options,today);
        var normalized=new ConditionNormalizer().apply(old,patch,"再加上白板",today,options);
        assertThat(normalized.conditions().get("requiredFeatures")).isEqualTo(List.of("PROJECTOR","WHITEBOARD"));
    }
}
