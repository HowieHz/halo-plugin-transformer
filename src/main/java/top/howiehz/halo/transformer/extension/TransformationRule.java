package top.howiehz.halo.transformer.extension;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import top.howiehz.halo.transformer.rule.MatchRule;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties("id")
@GVK(kind = "TransformationRule", group = "transformer.howiehz.top", version = "v1alpha1",
    singular = "transformationRule", plural = "transformationRules")
public class TransformationRule extends AbstractExtension {
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

    public String getId() {
        return getMetadata().getName();
    }

    public String getName() {
        if (this.name == null || this.name.isBlank()) {
            return getId();
        }
        return this.name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean getWrapMarker() {
        return wrapMarker;
    }

    /**
     * why: `position` 只属于 `SELECTOR` 规则的 DOM 操作语义；
     * 一旦切到 `HEAD/FOOTER`，旧的 selector-only 字段就不应继续污染持久化结果。
     */
    public void canonicalizeModeSpecificFieldsForStorage() {
        if (!Mode.SELECTOR.equals(getMode())) {
            match = "";
            position = Position.APPEND;
        }

        if (isSelectorRemove()) {
            snippetIds = new LinkedHashSet<>();
            wrapMarker = false;
            return;
        }

        if (snippetIds == null) {
            snippetIds = new LinkedHashSet<>();
        }
    }

    @JsonIgnore
    public boolean isSelectorRemove() {
        return Mode.SELECTOR.equals(getMode()) && Position.REMOVE.equals(getPosition());
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

    public enum Mode {
        HEAD,
        FOOTER,
        SELECTOR
    }

    public enum Position {
        APPEND,
        PREPEND,
        BEFORE,
        AFTER,
        REPLACE,
        REMOVE
    }
}
