package gov.election.viewer.repository;
import gov.election.viewer.entity.BallotImageView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
@Repository
public interface BallotImageViewRepository extends JpaRepository<BallotImageView, Long> {
    Optional<BallotImageView> findByImagePath(String imagePath);
    Optional<BallotImageView> findByImageName(String imageName);
    List<BallotImageView> findAllByOrderByImageNameAsc();
}
