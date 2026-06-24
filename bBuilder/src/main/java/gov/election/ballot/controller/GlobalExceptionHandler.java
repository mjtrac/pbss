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
package gov.election.ballot.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.ui.Model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catches exceptions thrown before or during controller method execution
 * and renders a readable error page instead of Spring's white-label page.
 *
 * Key cases handled:
 *  - MissingServletRequestParameterException: a required @RequestParam was absent
 *  - MethodArgumentTypeMismatchException: a parameter value could not be converted
 *    (most commonly: empty string "" submitted for a Long id field)
 *  - IllegalArgumentException: explicit validation failure in a controller method
 *  - Exception (catch-all): any other unexpected server error
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles the case where a required @RequestParam was completely absent from the request.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleMissingParam(MissingServletRequestParameterException ex,
                                     HttpServletRequest request,
                                     Model model) {
        String param = ex.getParameterName();
        String message = switch (param) {
            case "electionId"     -> "Please select an election before saving.";
            case "jurisdictionId" -> "Please select a jurisdiction before saving.";
            case "title"          -> "A title is required.";
            case "name"           -> "A name is required.";
            case "votingMethod"   -> "Please select a voting method.";
            default -> "Required field \"" + param + "\" was missing. Please fill in all required fields.";
        };
        log.warn("Missing parameter '" + param + "' on " + request.getRequestURI());
        model.addAttribute("errorTitle",   "Missing Required Field");
        model.addAttribute("errorMessage", message);
        model.addAttribute("backUrl",      request.getHeader("Referer"));
        return "error/form-error";
    }

    /**
     * Handles type conversion failures — most commonly an empty string ""
     * submitted for a Long field (e.g. the hidden id field on a new-record form).
     *
     * Root cause: <input type="hidden" name="id" th:value="${x.id}"/> renders
     * value="" when x.id is null. Spring then tries to parse "" as Long and fails.
     * This is now fixed in all templates by adding th:if="${x.id != null}" so the
     * field is absent (not empty) for new records. This handler is a safety net
     * for any cases that might still slip through.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                     HttpServletRequest request,
                                     Model model) {
        String paramName  = ex.getName();
        String paramValue = ex.getValue() != null ? ex.getValue().toString() : "(empty)";
        String targetType = ex.getRequiredType() != null
                            ? ex.getRequiredType().getSimpleName() : "unknown";

        String message;
        if (paramValue.isBlank() && "Long".equals(targetType)) {
            // Most common case: hidden id field submitted as empty string
            message = "A form field (\"" + paramName + "\") was submitted empty when a " +
                      "numeric value was expected. This usually means you are creating a " +
                      "new record and the form was not quite right. Please go back and try again.";
        } else {
            message = "Field \"" + paramName + "\" had value \"" + paramValue + "\" " +
                      "which could not be read as " + targetType + ". Please go back and correct it.";
        }

        log.warn("Type mismatch for '" + paramName + "'='" + paramValue +
                    "' on " + request.getRequestURI() + ": " + ex.getMessage());
        model.addAttribute("errorTitle",   "Invalid Field Value");
        model.addAttribute("errorMessage", message);
        model.addAttribute("backUrl",      request.getHeader("Referer"));
        return "error/form-error";
    }

    /**
     * Handles explicit validation failures thrown inside controller methods
     * (e.g. "Contest not found: 42", "Jurisdiction not found").
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleIllegalArgument(IllegalArgumentException ex,
                                        HttpServletRequest request,
                                        Model model) {
        log.warn("IllegalArgument on " + request.getRequestURI() + ": " + ex.getMessage());
        model.addAttribute("errorTitle",   "Invalid Request");
        model.addAttribute("errorMessage", ex.getMessage());
        model.addAttribute("backUrl",      request.getHeader("Referer"));
        return "error/form-error";
    }

    /**
     * 404 for missing routes — must be explicit so REST controllers get proper 404
     * instead of the HTML error page from the catch-all below.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoResourceFoundException ex,
                                  HttpServletRequest request,
                                  Model model) {
        log.warn("Not found: " + request.getRequestURI());
        model.addAttribute("errorTitle",   "Not Found");
        model.addAttribute("errorMessage", "No handler found for " + request.getRequestURI());
        model.addAttribute("backUrl",      "/");
        return "error/form-error";
    }

    /**
     * Catch-all for any other unexpected exception.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleGeneral(Exception ex,
                                HttpServletRequest request,
                                Model model) {
        log.error("Unhandled exception on " + request.getRequestURI() +
                   " [" + ex.getClass().getSimpleName() + "]: " + ex.getMessage());
        model.addAttribute("errorTitle",   "An Unexpected Error Occurred");
        model.addAttribute("errorMessage",
            ex.getClass().getSimpleName() +
            (ex.getMessage() != null ? ": " + ex.getMessage() : ""));
        model.addAttribute("backUrl",      request.getHeader("Referer"));
        return "error/form-error";
    }
}
