package com.erzbir.halo.injector.util;

import org.springframework.web.server.ServerWebInputException;

public class CodeSnippetValidationException extends ServerWebInputException {
    public CodeSnippetValidationException(String reason) {
        super(reason);
    }
}
