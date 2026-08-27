package com.xiaohua.service.ai;

import com.xiaohua.constant.AiPrompt;
import com.xiaohua.model.ai.ReportResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 闯关报告生成 AI 服务
 */
public interface ReportAiService {

    @SystemMessage(AiPrompt.GENERATE_REPORT_SYSTEM)
    @UserMessage("""
            ### 关卡名称
            {{levelName}}

            ## 关卡的需求描述
            {{levelDesc}}

            ## 用户选择的选项
            {{userOptions}}

            ### 本关卡的正确选项
            {{trueOptions}}

            ### 用户当前的薪资
            {{salary}}
            """)
    ReportResult generateReport(@V("levelName") String levelName,
                                @V("levelDesc") String levelDesc,
                                @V("userOptions") String userOptions,
                                @V("trueOptions") String trueOptions,
                                @V("salary") int salary);
}
