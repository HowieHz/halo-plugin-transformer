package top.howiehz.halo.transformer.core;

public interface ITransformationSnippet {
    String getId();

    String getName();

    String getDescription();

    String getCode();

    boolean isEnabled();
}
