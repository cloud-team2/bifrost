-- 파이프라인 한글 표시명(alias). 기술 이름(name)과 별개의 사용자 친화 라벨.
ALTER TABLE pipelines ADD COLUMN alias VARCHAR(100);
