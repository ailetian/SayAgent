package com.sayagent.knowledge.entity;

import com.sayagent.common.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 评测集（K1/K10）。
 *
 * <p>大白话：一套「标准问答对」——用来给 RAG 打分（召回率 /  Faithfulness / 拒答率），
 * 是上线前的正式体检题集。每条标注「是否应当拒答」，门禁据此判定拒答准确率。
 */
@Entity
@Table(name = "eval_dataset")
@SQLRestriction("deleted = 0")
@SQLDelete(sql = "UPDATE `eval_dataset` SET deleted = 1 WHERE id = ?")
@Getter
@Setter
@NoArgsConstructor
public class EvalDataset extends BaseEntity {

    /** 知识库 id。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 评测问题。 */
    @Column(name = "question", nullable = false, length = 1000)
    private String question;

    /** 题型（事实 / 推理 / 拒答…）。 */
    @Column(name = "type", length = 20)
    private String type;

    /** 关键词（命中校验用）。 */
    @Column(name = "keywords", length = 255)
    private String keywords;

    /** 期望答案。 */
    @Column(name = "expected", columnDefinition = "mediumtext")
    private String expected;

    /** 是否应拒答（门禁用）。 */
    @Column(name = "should_reject")
    private Boolean shouldReject;
}
