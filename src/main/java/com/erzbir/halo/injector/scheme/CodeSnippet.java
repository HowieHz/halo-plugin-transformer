package com.erzbir.halo.injector.scheme;

import com.erzbir.halo.injector.core.ICodeSnippet;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(kind = "CodeSnippet", group = "injector.erzbir.com",
        version = "v1alpha1", singular = "codeSnippet", plural = "codeSnippets")
public class CodeSnippet extends AbstractExtension implements ICodeSnippet {
    private String name = "";
    private String code = "";
    private String description = "";
    private Boolean enabled = true;
    private Integer sortOrder;
    private Set<String> ruleIds = new LinkedHashSet<>();
    @JsonIgnore
    private final Set<String> unknownFields = new LinkedHashSet<>();

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String getId() {
        return this.getMetadata().getName();
    }

    @Override
    public String getName() {
        if (name == null || name.isBlank()) {
            return getId();
        }
        return name;
    }

    /**
     * why: 这里只是运行期快速判断，不属于持久化模型字段；
     * 若被序列化成 `valid` 回给前端，再被原样写回，就会污染写接口 payload。
     */
    @JsonIgnore
    public boolean isValid() {
        return code != null && !code.isBlank();
    }

    @JsonAnySetter
    public void recordUnknownField(String key, Object ignoredValue) {
        unknownFields.add(key);
    }
}
