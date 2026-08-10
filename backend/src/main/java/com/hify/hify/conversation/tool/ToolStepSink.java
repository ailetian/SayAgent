package com.hify.hify.conversation.tool;

/**
 * 工具循环进度事件出口（M8/T3，函数式接口）。
 *
 * <p>大白话：编排循环每调一个工具，都要给前端发个「正在调用 / 已返回」的进度小条。
 * 这个接口把"发进度"这件事和具体怎么发（SSE / 日志 / 其它）解耦——{@code ConversationService}
 * 包成 {@code sendStep(emitter, label, status, "tool")} 传进来，循环只管调 {@code step(label, status)}。
 */
@FunctionalInterface
public interface ToolStepSink {

    /** 发一条工具进度：label=文案，status=running(进行中)/done(完成)/error(失败)。 */
    void step(String label, String status);
}
