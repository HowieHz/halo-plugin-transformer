package top.howiehz.halo.transformer.config;

import java.util.List;
import run.halo.app.extension.controller.Controller;

/**
 * why: 插件生命周期只能管理自己注册的 controllers；
 * 用专有聚合对象收口后，就不会随着 Spring 容器里新增别的 controller 而越管越宽。
 */
public record TransformerControllers(List<Controller> items) {
    public void startAll() {
        items.forEach(Controller::start);
    }

    public void stopAll() {
        items.forEach(Controller::dispose);
    }
}
