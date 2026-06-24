package gov.election.viewer.entity;
import jakarta.persistence.*;
@Entity @Table(name = "vote_opportunity")
public class VoteOpportunityView {
    @Id private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ballot_image_id") private BallotImageView ballotImage;
    @Column(name = "contest_id")      private Long contestId;
    @Column(name = "candidate_id_fk") private Long candidateIdFk;
    @Column(name = "abs_left")        private int absLeft;
    @Column(name = "abs_top")         private int absTop;
    @Column(name = "image_x")         private int imageX;
    @Column(name = "image_y")         private int imageY;
    @Column(name = "indicator_width") private int indicatorWidth;
    @Column(name = "indicator_height")private int indicatorHeight;
    @Column(name = "warp_dpi")        private int warpDpi;
    @Column(name = "vote_status")     private String voteStatus;
    public Long   getId()             { return id; }
    public BallotImageView getBallotImage() { return ballotImage; }
    public Long   getContestId()      { return contestId; }
    public Long   getCandidateIdFk()  { return candidateIdFk; }
    public int    getAbsLeft()        { return absLeft; }
    public int    getAbsTop()         { return absTop; }
    public int    getImageX()         { return imageX; }
    public int    getImageY()         { return imageY; }
    public int    getIndicatorWidth() { return indicatorWidth; }
    public int    getIndicatorHeight(){ return indicatorHeight; }
    public int    getWarpDpi()        { return warpDpi; }
    public String getVoteStatus()     { return voteStatus; }
}
