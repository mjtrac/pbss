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
package com.mjtrac.ballot.service;

import com.mjtrac.ballot.model.BallotCombination;
import com.mjtrac.ballot.model.PrintLog;
import com.mjtrac.ballot.model.User;
import com.mjtrac.ballot.repository.PrintLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and queries the print audit log.
 * Every ballot print event is stored here for election security compliance.
 */
@Service
public class PrintLogService {

    private static final Logger log = LoggerFactory.getLogger(PrintLogService.class);

    private final PrintLogRepository printLogRepo;
    private final MachineIdentityService machineIdentity;

    public PrintLogService(PrintLogRepository printLogRepo, MachineIdentityService machineIdentity) {
        this.printLogRepo = printLogRepo;
        this.machineIdentity = machineIdentity;
    }

    @Transactional
    public PrintLog record(User user, BallotCombination combination,
                           String paperSize, int copies) {
        PrintLog entry = new PrintLog();
        entry.setPrintedBy(user);
        entry.setBallotCombination(combination);
        entry.setPaperSize(paperSize);
        entry.setCopies(copies);
        // The GUI's own asserted "Printed by" user, plus the OS-level
        // identity of who/what machine actually ran the print -- these can
        // legitimately differ (e.g. one shared login attributing prints to
        // different pbss Users), and both matter for an audit trail.
        entry.setOsUsername(machineIdentity.osUsername());
        entry.setHostname(machineIdentity.hostname());
        entry.setMachineSerial(machineIdentity.machineSerial());
        entry = printLogRepo.save(entry);

        log.info("Ballot printed: printedByUser={} osUsername={} hostname={} machineSerial={} "
                + "combinationId={} paperSize={} copies={} printedAt={}",
            user != null ? user.getUsername() : null, entry.getOsUsername(), entry.getHostname(),
            entry.getMachineSerial() != null ? entry.getMachineSerial() : "(unavailable)",
            combination != null ? combination.getId() : null, paperSize, copies, entry.getPrintedAt());

        return entry;
    }

    public List<PrintLog> getByUser(Long userId) {
        return printLogRepo.findByPrintedByIdOrderByPrintedAtDesc(userId);
    }

    public List<PrintLog> getByCombination(Long combinationId) {
        return printLogRepo.findByBallotCombinationIdOrderByPrintedAtDesc(combinationId);
    }
}
