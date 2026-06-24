package gov.election.viewer.entity;
import jakarta.persistence.*;
@Entity @Table(name = "candidate")
public class CandidateView {
    @Id private Long id;
    @Column(name = "contest_id")     private Long contestId;
    @Column(name = "candidate_name") private String candidateName;
    @Column(name = "write_in")       private boolean writeIn;
    public Long getId() { return id; }
    public Long getContestId() { return contestId; }
    public String getCandidateName() { return candidateName; }
    public boolean isWriteIn() { return writeIn; }
}
