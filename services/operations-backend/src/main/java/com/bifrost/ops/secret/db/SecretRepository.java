package com.bifrost.ops.secret.db;

import org.springframework.data.jpa.repository.JpaRepository;

interface SecretRepository extends JpaRepository<SecretEntity, String> {
}
