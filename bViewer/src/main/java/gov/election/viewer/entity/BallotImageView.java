package gov.election.viewer.entity;
import jakarta.persistence.*;
@Entity @Table(name = "ballot_image")
public class BallotImageView {
    @Id private Long id;
    @Column(name = "image_path")      private String imagePath;
    @Column(name = "image_name")      private String imageName;
    @Column(name = "dpi")             private int dpi;
    @Column(name = "was_rotated")     private boolean wasRotated;
    @Column(name = "warp_dpi")        private int warpDpi;
    @Column(name = "canonical_width") private int canonicalWidth;
    @Column(name = "canonical_height")private int canonicalHeight;
    @Column(name = "corner_marks")    private String cornerMarks;
    public Long   getId()              { return id; }
    public String getImagePath()       { return imagePath; }
    public String getImageName()       { return imageName; }
    public int    getDpi()             { return dpi; }
    public boolean isWasRotated()       { return wasRotated; }
    public int    getWarpDpi()         { return warpDpi; }
    public int    getCanonicalWidth()  { return canonicalWidth; }
    public int    getCanonicalHeight() { return canonicalHeight; }
    public String getCornerMarks()     { return cornerMarks; }
}
