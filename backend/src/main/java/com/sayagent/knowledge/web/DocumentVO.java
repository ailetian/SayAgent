package com.sayagent.knowledge.web;

/**
 * 文档视图对象（M5/T5），用于上传接口返回。
 *
 * <p>大白话：前端要的「文档长什么样」的精简盒子，避免把数据库实体直接序列化出去（§7.11 规则37：敏感字段不过前端）。
 *
 * @param docId      文档业务 ID
 * @param status     文档状态（枚举名：INDEXING / INDEXED / FAILED …）
 * @param chunkCount 切片数
 */
public record DocumentVO(String docId, String status, int chunkCount) {
}
