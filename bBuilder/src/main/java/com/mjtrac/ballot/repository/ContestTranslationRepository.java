package com.mjtrac.ballot.repository;
import com.mjtrac.ballot.model.ContestTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ContestTranslationRepository extends JpaRepository<ContestTranslation, Long> {
    Optional<ContestTranslation> findByContestIdAndLanguageCode(Long contestId, String code);
    List<ContestTranslation> findByContestId(Long contestId);
    void deleteByContestId(Long contestId);
}
