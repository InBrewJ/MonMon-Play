package controllers;

import models.SearchRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.libs.Json;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SearchResult;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static helpers.UserHelpers.getSimpleUserProfile;

public class SearchController extends Controller {

    private final SearchRepository searchRepository;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public SearchController(SearchRepository searchRepository, HttpExecutionContext ec) {
        this.searchRepository = searchRepository;
        this.ec = ec;
    }

    /**
     * Unified app-wide search endpoint.
     * Returns matching accounts, outgoings, incomings, balances, meal plans, pots, savings targets, etc.
     */
    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> search(String q, final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId()) || q == null || q.trim().isEmpty()) {
            return CompletableFuture.completedFuture(ok(Json.toJson(Collections.emptyList())));
        }

        return searchRepository.search(sup.getUserId(), q, 25)
                .thenApplyAsync(results -> ok(Json.toJson(results)), ec.current());
    }
}
