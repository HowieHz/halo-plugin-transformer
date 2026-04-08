package top.howiehz.halo.transformer.scheme;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import top.howiehz.halo.transformer.core.ITransformationSnippet;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties("id")
@GVK(kind = "TransformationSnippet", group = "transformer.howiehz.top",
    version = "v1alpha1", singular = "transformationSnippet", plural = "transformationSnippets")
public class TransformationSnippet extends AbstractExtension implements ITransformationSnippet {
    @JsonIgnore
    private final Set<String> unknownFields = new LinkedHashSet<>();
    private String name = "";
    private String code = "";
    private String description = "";
    private Boolean enabled = true;

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
