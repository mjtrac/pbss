package gov.election.viewer.repository;
import gov.election.viewer.entity.VoteOpportunityView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface VoteOpportunityViewRepository extends JpaRepository<VoteOpportunityView, Long> {
    List<VoteOpportunityView> findByBallotImageId(Long imageId);
}
