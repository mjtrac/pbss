/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package gov.election.ballot.config;

import gov.election.ballot.model.*;
import gov.election.ballot.repository.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.security.SecureRandom;

/**
 * Seeds the database on first startup (only when users table is empty).
 *
 * Seeded data:
 *   Jurisdiction : Sample County
 *   User         : admin / ChangeMe123!
 *   Election     : Sample General Election
 *   Ballot Types : Precinct, Mail-In, Absentee, Provisional
 *   Party        : Everyone  (for general / nonpartisan elections)
 *   Regions      : p1, p2, p3  (SinglePrecincts)
 *                  g12          (PrecinctGroup: p1, p2)
 *                  g3           (PrecinctGroup: p3)
 *   Contest      : Sample Contest  (Plurality, assigned to g12 and g3 = all precincts)
 *   Candidates   : Alice (order 1), Bob (order 2)
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository                userRepo;
    private final JurisdictionRepository        jurisdictionRepo;
    private final ElectionRepository            electionRepo;
    private final BallotTypeRepository          ballotTypeRepo;
    private final PartyRepository               partyRepo;
    private final RegionRepository              regionRepo;
    private final ContestRepository             contestRepo;
    private final CandidateRepository           candidateRepo;
    private final BallotDesignTemplateRepository templateRepo;
    private final BallotCombinationRepository   combinationRepo;
    private final PasswordEncoder               passwordEncoder;

    private static final String RESET_FLAG = "reset.admin.password";
    private static final String CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#%&*";

    private final org.springframework.core.env.Environment env;

    public DataInitializer(UserRepository userRepo,
                           JurisdictionRepository jurisdictionRepo,
                           ElectionRepository electionRepo,
                           BallotTypeRepository ballotTypeRepo,
                           PartyRepository partyRepo,
                           RegionRepository regionRepo,
                           ContestRepository contestRepo,
                           CandidateRepository candidateRepo,
                           BallotDesignTemplateRepository templateRepo,
                           BallotCombinationRepository combinationRepo,
                           PasswordEncoder passwordEncoder,
                           org.springframework.core.env.Environment env) {
        this.userRepo         = userRepo;
        this.jurisdictionRepo = jurisdictionRepo;
        this.electionRepo     = electionRepo;
        this.ballotTypeRepo   = ballotTypeRepo;
        this.partyRepo        = partyRepo;
        this.regionRepo       = regionRepo;
        this.contestRepo      = contestRepo;
        this.candidateRepo    = candidateRepo;
        this.templateRepo     = templateRepo;
        this.combinationRepo  = combinationRepo;
        this.passwordEncoder  = passwordEncoder;
        this.env              = env;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // ── Admin password reset ──────────────────────────────────────────
        // Triggered by: -Dreset.admin.password=true on the JVM command line.
        // Generates a secure random password, sets it, and prints it ONCE to
        // stdout. The server then exits so the flag cannot be replayed.
        if ("true".equalsIgnoreCase(env.getProperty(RESET_FLAG))) {
            String newPassword = generatePassword(16);
            userRepo.findByUsername("admin").ifPresentOrElse(admin -> {
                admin.setPasswordHash(passwordEncoder.encode(newPassword));
                userRepo.save(admin);
                System.out.println();
                System.out.println("╔══════════════════════════════════════════════════╗");
                System.out.println("║         bBuilder — Admin Password Reset          ║");
                System.out.println("╠══════════════════════════════════════════════════╣");
                System.out.printf( "║  New password: %-34s║%n", newPassword);
                System.out.println("║  Log in and change it immediately.               ║");
                System.out.println("╚══════════════════════════════════════════════════╝");
                System.out.println();
            }, () -> {
                System.err.println("[bBuilder] ERROR: no admin user found — cannot reset password.");
            });
            // Exit after reset so the flag is not left active
            System.exit(0);
        }

        if (userRepo.count() > 0) return;

        // ── Jurisdiction ──────────────────────────────────────────────────
        Jurisdiction county = new Jurisdiction();
        county.setName("Sample County");
        county.setAddress("1 Courthouse Square, Sampleville, CA 00000");
        county.setContactEmail("elections@samplecounty.gov");
        county.setGeneralVotingInstructions(
            "To vote: completely fill in the oval next to your choice. " +
            "For write-ins, fill in the oval and print the name on the line provided.");
        county = jurisdictionRepo.save(county);

        // ── Admin user ────────────────────────────────────────────────────
        User admin = new User();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode("ChangeMe123!"));
        admin.setRoles(Set.of(User.Role.ADMIN));
        admin.setEnabled(true);
        admin.setJurisdiction(county);
        userRepo.save(admin);

        // ── Ballot types ──────────────────────────────────────────────────
        for (String[] bt : new String[][]{
                {"Precinct",    "Voted in person at a polling place"},
                {"Mail-In",     "Returned by mail before election day"},
                {"Absentee",    "Absentee ballot requested in advance"},
                {"Provisional", "Provisional — eligibility pending verification"}}) {
            BallotType b = new BallotType();
            b.setJurisdiction(county);
            b.setName(bt[0]);
            b.setDescription(bt[1]);
            ballotTypeRepo.save(b);
        }

        // ── Party: Everyone ───────────────────────────────────────────────
        Party everyone = new Party();
        everyone.setJurisdiction(county);
        everyone.setName("Everyone");
        everyone.setAbbreviation("ALL");
        partyRepo.save(everyone);

        // ── Sample election ───────────────────────────────────────────────
        Election election = new Election();
        election.setName("Sample General Election");
        election.setJurisdiction(county);
        election.setElectionDate(LocalDate.now().plusMonths(3));
        election.setElectionType(Election.ElectionType.GENERAL);
        election.setUniformBallot(false);
        election = electionRepo.save(election);

        // ── SinglePrecincts: p1, p2, p3 ──────────────────────────────────
        Region p1 = regionRepo.save(singlePrecinct("p1", county));
        Region p2 = regionRepo.save(singlePrecinct("p2", county));
        Region p3 = regionRepo.save(singlePrecinct("p3", county));

        // ── PrecinctGroup: g12 (p1, p2) ──────────────────────────────────
        Region g12 = new Region();
        g12.setName("g12");
        g12.setJurisdiction(county);
        g12.setRegionType(Region.RegionType.PRECINCT_GROUP);
        g12.setGroupType("DISTRICT");
        g12.setDescription("Sample group covering p1 and p2");
        g12.setMembers(List.of(p1, p2));
        g12 = regionRepo.save(g12);

        // ── PrecinctGroup: g3 (p3) ────────────────────────────────────────
        Region g3 = new Region();
        g3.setName("g3");
        g3.setJurisdiction(county);
        g3.setRegionType(Region.RegionType.PRECINCT_GROUP);
        g3.setGroupType("DISTRICT");
        g3.setDescription("Sample group covering p3 only");
        g3.setMembers(List.of(p3));
        g3 = regionRepo.save(g3);

        // ── Sample Contest — assigned to both PrecinctGroups (covers all precincts) ──
        Contest contest = new Contest();
        contest.setTitle("Sample Contest");
        contest.setElection(election);
        contest.setVotingMethod(Contest.VotingMethod.PLURALITY);
        contest.setMaxChoices(1);
        contest.setDisplayOrder(1);
        contest.setInstructions("Vote for one");
        // Assign to both groups so all three precincts are covered
        contest.setAssignedRegions(List.of(g12, g3));
        contest = contestRepo.save(contest);

        // ── Candidates: Alice (1), Bob (2) ────────────────────────────────
        Candidate alice = new Candidate();
        alice.setContest(contest);
        alice.setName("Alice");
        alice.setDisplayOrder(1);
        candidateRepo.save(alice);

        Candidate bob = new Candidate();
        bob.setContest(contest);
        bob.setName("Bob");
        bob.setDisplayOrder(2);
        candidateRepo.save(bob);

        // ── Default Ballot Design Template (8.5x11 Letter, 3 columns, OVAL) ─────
        BallotDesignTemplate template = new BallotDesignTemplate();
        template.setElection(election);
        template.setPaperSize(BallotDesignTemplate.PaperSize.LETTER_8_5x11);
        template.setVoteIndicatorStyle(BallotDesignTemplate.VoteIndicatorStyle.OVAL);
        template.setColumns(3);
        // All font sizes, bold/italic flags use model defaults — nothing to set.
        template = templateRepo.save(template);

        // ── Ballot Combination: election + Everyone party + p1 + Precinct type ─
        // Use p1 as the representative precinct for the sample combination.
        BallotType precinctType = ballotTypeRepo
            .findByJurisdictionIdOrderByName(county.getId()).stream()
            .filter(bt -> "Precinct".equalsIgnoreCase(bt.getName()))
            .findFirst().orElse(null);
        if (precinctType != null) {
            BallotCombination combo = new BallotCombination();
            combo.setElection(election);
            combo.setRegion(p1);
            combo.setParty(everyone);
            combo.setBallotType(precinctType);
            combinationRepo.save(combo);
        }

        System.out.println("""
            ============================================================
             Sample data seeded:
               Jurisdiction : Sample County
               Election     : Sample General Election
               Ballot Types : Precinct, Mail-In, Absentee, Provisional
               Party        : Everyone
               Regions      : p1, p2, p3  [SinglePrecinct]
                              g12  [PrecinctGroup: p1, p2]
                              g3   [PrecinctGroup: p3]
               Contest      : Sample Contest (Plurality, covers all precincts)
               Candidates   : Alice (1), Bob (2)
               Template     : Default (8.5x11, 3 columns, Oval indicators)
               Combination  : p1 + Everyone + Precinct
               Username     : admin
               Password     : ChangeMe123!
               
               To reset the admin password, restart with:
               -Dreset.admin.password=true
               A new random password will be printed to stdout.
            ============================================================
            """);
    }

    private Region singlePrecinct(String name, Jurisdiction j) {
        Region r = new Region();
        r.setName(name);
        r.setJurisdiction(j);
        r.setRegionType(Region.RegionType.SINGLE_PRECINCT);
        return r;
    }
    private String generatePassword(int length) {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
