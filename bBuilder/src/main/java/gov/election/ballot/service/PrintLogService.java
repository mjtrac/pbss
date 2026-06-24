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
package gov.election.ballot.service;

import gov.election.ballot.model.BallotCombination;
import gov.election.ballot.model.PrintLog;
import gov.election.ballot.model.User;
import gov.election.ballot.repository.PrintLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and queries the print audit log.
 * Every ballot print event is stored here for election security compliance.
 */
@Service
public class PrintLogService {

    private final PrintLogRepository printLogRepo;

    public PrintLogService(PrintLogRepository printLogRepo) {
        this.printLogRepo = printLogRepo;
    }

    @Transactional
    public PrintLog record(User user, BallotCombination combination,
                           String paperSize, int copies) {
        PrintLog log = new PrintLog();
        log.setPrintedBy(user);
        log.setBallotCombination(combination);
        log.setPaperSize(paperSize);
        log.setCopies(copies);
        return printLogRepo.save(log);
    }

    public List<PrintLog> getByUser(Long userId) {
        return printLogRepo.findByPrintedByIdOrderByPrintedAtDesc(userId);
    }

    public List<PrintLog> getByCombination(Long combinationId) {
        return printLogRepo.findByBallotCombinationIdOrderByPrintedAtDesc(combinationId);
    }
}
