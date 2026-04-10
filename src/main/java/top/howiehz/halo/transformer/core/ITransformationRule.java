package top.howiehz.halo.transformer.core;

import java.util.Set;

public interface ITransformationRule {
    String getId();

    String getName();

    String getDescription();

    boolean isEnabled();

    Mode getMode();

    String getMatch();

    MatchRule getMatchRule();

    Position getPosition();

    Set<String> getSnippetIds();

    boolean getWrapMarker();

    enum Mode {
        HEAD, FOOTER, SELECTOR

    }

    enum Position {
        APPEND, PREPEND, BEFORE, AFTER, REPLACE, REMOVE
    }
}
