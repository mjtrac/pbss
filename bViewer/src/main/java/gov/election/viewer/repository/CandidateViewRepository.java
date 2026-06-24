package gov.election.viewer.repository;
import gov.election.viewer.entity.CandidateView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface CandidateViewRepository extends JpaRepository<CandidateView, Long> {
    List<CandidateView> findByContestId(Long contestId);
}
