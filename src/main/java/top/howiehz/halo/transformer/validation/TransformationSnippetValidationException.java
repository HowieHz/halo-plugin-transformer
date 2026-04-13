package top.howiehz.halo.transformer.validation;

import org.springframework.web.server.ServerWebInputException;

public class TransformationSnippetValidationException extends ServerWebInputException {
    public TransformationSnippetValidationException(String reason) {
        super(reason);
    }
}
