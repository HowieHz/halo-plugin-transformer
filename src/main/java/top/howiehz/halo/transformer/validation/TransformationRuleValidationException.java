package top.howiehz.halo.transformer.validation;

import org.springframework.web.server.ServerWebInputException;

public class TransformationRuleValidationException extends ServerWebInputException {
    public TransformationRuleValidationException(String reason) {
        super(reason);
    }
}
