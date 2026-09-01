package com.xiaohua.model.dto.level;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 关卡作答请求体
 */
@Data
public class LevelSubmitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关卡 id
     */
    private Long levelId;

    /**
     * 用户选中的选项名称列表
     */
    private List<String> userOptions;
}
