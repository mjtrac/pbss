package com.mjtrac.ballot.repository;
import com.mjtrac.ballot.model.CandidateTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CandidateTranslationRepository extends JpaRepository<CandidateTranslation, Long> {
    Optional<CandidateTranslation> findByCandidateIdAndLanguageCode(Long candidateId, String code);
    List<CandidateTranslation> findByCandidateId(Long candidateId);
    void deleteByCandidateId(Long candidateId);
}
