package gov.election.viewer.repository;
import gov.election.viewer.entity.ContestView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
@Repository
public interface ContestViewRepository extends JpaRepository<ContestView, Long> {}
