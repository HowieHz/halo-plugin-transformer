package com.erzbir.halo.injector.scheme;

import com.erzbir.halo.injector.core.IInjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties("id")
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
    @JsonIgnore
    private boolean legacyIdSelectorMigrationPending = false;

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
     * why: 开发版虽然已删除独立 `ID` 模式，但已有存量规则仍可能带着旧值启动插件；
     * 这里在读取期把旧 `ID` 自动迁成 `SELECTOR`，并继续触发 `match` 的 `#id` 兼容改写。
     */
    @JsonSetter("mode")
    void setModeFromJson(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            this.mode = Mode.FOOTER;
            this.legacyIdSelectorMigrationPending = false;
            return;
        }
        String normalizedMode = rawMode.trim().toUpperCase(Locale.ROOT);
        if ("ID".equals(normalizedMode)) {
            this.mode = Mode.SELECTOR;
            this.legacyIdSelectorMigrationPending = true;
            this.match = migrateLegacyIdMatch(this.match);
            return;
        }
        this.mode = Mode.valueOf(normalizedMode);
        this.legacyIdSelectorMigrationPending = false;
    }

    /**
     * why: 旧 `ID` 模式保存的是纯 id 值；迁到 `SELECTOR` 后若仍保留原值，
     * 选择器语义会被误解成标签名，因此这里自动补成 `#id`。
     */
    @JsonSetter("match")
    void setMatchFromJson(String rawMatch) {
        this.match = legacyIdSelectorMigrationPending
                ? migrateLegacyIdMatch(rawMatch)
                : defaultString(rawMatch);
    }

    private String migrateLegacyIdMatch(String rawMatch) {
        String normalizedMatch = defaultString(rawMatch).trim();
        if (normalizedMatch.isBlank()) {
            return "";
        }
        return normalizedMatch.startsWith("#") ? normalizedMatch : "#" + normalizedMatch;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
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
