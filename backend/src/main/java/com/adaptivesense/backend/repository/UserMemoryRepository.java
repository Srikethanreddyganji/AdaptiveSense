package com.adaptivesense.backend.repository;

import com.adaptivesense.backend.entity.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMemoryRepository
        extends JpaRepository<UserMemory, Long> {

    Optional<UserMemory> findByUserId(Long userId);
}