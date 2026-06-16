---
doc_id: glossary:cdc-replication-slot
doc_type: glossary
title: CDC Replication Slot
tags: [cdc, replication-slot, source]
source: synthetic
---

CDC replication slot은 source database의 변경 로그를 connector가 안정적으로 읽기 위해 잡는 위치 정보다. Slot lag가 커지면 source WAL 보관량이 늘어나고 database disk pressure로 번질 수 있다. 인증 오류나 network 단절로 connector가 오래 멈추면 slot은 유지되지만 변경분 소비가 지연된다. 운영자는 slot active 상태, retained WAL 크기, connector offset, source connection error를 같이 확인한다. Bifrost는 replication slot 지연을 source와 connector 경계의 중요한 evidence로 다룬다.
