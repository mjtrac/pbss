package gov.election.viewer.entity;
import jakarta.persistence.*;
@Entity @Table(name = "contest")
public class ContestView {
    @Id private Long id;
    @Column(name = "contest_title") private String contestTitle;
    @Column(name = "contest_type")  private String contestType;
    public Long getId() { return id; }
    public String getContestTitle() { return contestTitle; }
    public String getContestType()  { return contestType; }
}
