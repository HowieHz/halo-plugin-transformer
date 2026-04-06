package com.erzbir.halo.injector.core;

public interface ICodeSnippet {
    String getId();

    String getName();

    String getDescription();

    String getCode();

    boolean isEnabled();
}
