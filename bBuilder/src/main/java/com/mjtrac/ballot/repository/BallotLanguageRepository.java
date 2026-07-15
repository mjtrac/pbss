package com.mjtrac.ballot.repository;
import com.mjtrac.ballot.model.BallotLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BallotLanguageRepository extends JpaRepository<BallotLanguage, Long> {
    List<BallotLanguage> findByJurisdictionIdOrderByDisplayOrderAsc(Long jurisdictionId);
    Optional<BallotLanguage> findByJurisdictionIdAndLanguageCode(Long jurisdictionId, String code);
}
