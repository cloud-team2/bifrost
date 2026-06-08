package com.bifrost.ops.pipeline.dto;

/**
 * 이벤트 타입 분포 시계열 한 점(#126). Debezium create/update/delete 이벤트 증가분.
 *
 * @param timestamp epoch millis
 * @param insert    구간 내 INSERT(create) 이벤트 수
 * @param update    구간 내 UPDATE 이벤트 수
 * @param delete    구간 내 DELETE 이벤트 수
 */
public record EventDistPoint(long timestamp, long insert, long update, long delete) {}
