package com.erzbir.halo.injector.scheme;

import com.erzbir.halo.injector.core.IInjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(kind = "InjectionRule", group = "injector.erzbir.com", version = "v1alpha1",
        singular = "injectionRule", plural = "injectionRules")
public class InjectionRule extends AbstractExtension implements IInjectionRule {
    /**
     * why: 新建规则默认应落在最低优先级预设，而不是一创建就抢到既有规则前面；
     * 只有用户显式调高优先级时，才改变同阶段执行顺序。
     */
    public static final int DEFAULT_RUNTIME_ORDER = 2147483645;

    private String name = "";
    private String description = "";
    private Boolean enabled = true;
    private Mode mode = Mode.FOOTER;
    private String match = "";
    private MatchRule matchRule = MatchRule.defaultRule();
    private Position position = Position.APPEND;
    private boolean wrapMarker = true;
    private int runtimeOrder = DEFAULT_RUNTIME_ORDER;
    private Set<String> snippetIds = new LinkedHashSet<>();

    @Override
    public String getId() {
        return getMetadata().getName();
    }

    @Override
    public String getName() {
        if (this.name == null || this.name.isBlank()) {
            return getId();
        }
        return this.name;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean getWrapMarker() {
        return wrapMarker;
    }

    /**
     * why: 这里只是运行期辅助状态，不应出现在 JSON 模型里；
     * 否则前端读取后再保存，会把 `valid` 当成真实字段回传，触发严格校验报错。
     */
    @JsonIgnore
    public boolean isValid() {
        boolean targetValid = !Mode.SELECTOR.equals(getMode()) || !getMatch().isBlank();
        return targetValid && matchRule != null && matchRule.isValid();
    }
}
