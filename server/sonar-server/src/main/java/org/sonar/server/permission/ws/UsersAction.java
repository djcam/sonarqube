/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.permission.ws;

import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.Paging;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.PermissionQuery;
import org.sonar.db.permission.UserPermissionDto;
import org.sonar.db.user.UserDto;
import org.sonar.server.issue.ws.AvatarResolver;
import org.sonar.server.permission.ProjectId;
import org.sonar.server.user.UserSession;
import org.sonarqube.ws.Permissions;
import org.sonarqube.ws.Permissions.UsersWsResponse;

import static com.google.common.base.Strings.emptyToNull;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.sonar.db.permission.PermissionQuery.DEFAULT_PAGE_SIZE;
import static org.sonar.db.permission.PermissionQuery.RESULTS_MAX_SIZE;
import static org.sonar.db.permission.PermissionQuery.SEARCH_QUERY_MIN_LENGTH;
import static org.sonar.server.permission.PermissionPrivilegeChecker.checkProjectAdmin;
import static org.sonar.server.permission.ws.RequestValidator.validateGlobalPermission;
import static org.sonar.server.permission.ws.WsParameters.createOrganizationParameter;
import static org.sonar.server.permission.ws.WsParameters.createProjectParameters;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_ORGANIZATION;
import static org.sonarqube.ws.client.permission.PermissionsWsParameters.PARAM_PERMISSION;

public class UsersAction implements PermissionsWsAction {

  private final DbClient dbClient;
  private final UserSession userSession;
  private final PermissionWsSupport wsSupport;
  private final AvatarResolver avatarResolver;
  private final WsParameters wsParameters;
  private final RequestValidator requestValidator;

  public UsersAction(DbClient dbClient, UserSession userSession, PermissionWsSupport wsSupport, AvatarResolver avatarResolver, WsParameters wsParameters,
    RequestValidator requestValidator) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.wsSupport = wsSupport;
    this.avatarResolver = avatarResolver;
    this.wsParameters = wsParameters;
    this.requestValidator = requestValidator;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction("users")
      .setSince("5.2")
      .setDescription("Lists the users with their permissions as individual users rather than through group affiliation.<br>" +
        "This service defaults to global permissions, but can be limited to project permissions by providing project id or project key.<br> " +
        "This service defaults to all users, but can be limited to users with a specific permission by providing the desired permission.<br>" +
        "Requires one of the following permissions:" +
        "<ul>" +
        "<li>'Administer System'</li>" +
        "<li>'Administer' rights on the specified project</li>" +
        "</ul>")
      .addPagingParams(DEFAULT_PAGE_SIZE, RESULTS_MAX_SIZE)
      .setChangelog(
        new Change("7.4", "The response list is returning all users even those without permissions, the users with permission are at the top of the list."))
      .setInternal(true)
      .setResponseExample(getClass().getResource("users-example.json"))
      .setHandler(this);

    action.createParam(Param.TEXT_QUERY)
      .setMinimumLength(SEARCH_QUERY_MIN_LENGTH)
      .setDescription("Limit search to user names that contain the supplied string. <br/>")
      .setExampleValue("eri");

    createOrganizationParameter(action).setSince("6.2");
    wsParameters.createPermissionParameter(action).setRequired(false);
    createProjectParameters(action);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    try (DbSession dbSession = dbClient.openSession(false)) {
      OrganizationDto org = wsSupport.findOrganization(dbSession, request.param(PARAM_ORGANIZATION));
      Optional<ProjectId> projectId = wsSupport.findProjectId(dbSession, request);
      checkProjectAdmin(userSession, org.getUuid(), projectId);

      PermissionQuery query = buildPermissionQuery(request, org, projectId);
      List<UserDto> users = findUsers(dbSession, query);
      int total = dbClient.userPermissionDao().countUsersByQuery(dbSession, query);
      List<UserPermissionDto> userPermissions = findUserPermissions(dbSession, org, users, projectId);
      Paging paging = Paging.forPageIndex(request.mandatoryParamAsInt(Param.PAGE)).withPageSize(query.getPageSize()).andTotal(total);
      UsersWsResponse usersWsResponse = buildResponse(users, userPermissions, paging);
      writeProtobuf(usersWsResponse, request, response);
    }
  }

  private PermissionQuery buildPermissionQuery(Request request, OrganizationDto organization, Optional<ProjectId> project) {
    String textQuery = request.param(Param.TEXT_QUERY);
    String permission = request.param(PARAM_PERMISSION);
    PermissionQuery.Builder permissionQuery = PermissionQuery.builder()
      .setOrganizationUuid(organization.getUuid())
      .setPermission(permission)
      .setPageIndex(request.mandatoryParamAsInt(Param.PAGE))
      .setPageSize(request.mandatoryParamAsInt(Param.PAGE_SIZE))
      .setSearchQuery(textQuery);
    project.ifPresent(projectId -> permissionQuery.setComponent(projectId.getUuid(), projectId.getId()));
    if (permission != null) {
      if (project.isPresent()) {
        requestValidator.validateProjectPermission(permission);
      } else {
        validateGlobalPermission(permission);
      }
    }

    return permissionQuery.build();
  }

  private UsersWsResponse buildResponse(List<UserDto> users, List<UserPermissionDto> userPermissions, Paging paging) {
    Multimap<Integer, String> permissionsByUserId = TreeMultimap.create();
    userPermissions.forEach(userPermission -> permissionsByUserId.put(userPermission.getUserId(), userPermission.getPermission()));

    UsersWsResponse.Builder response = UsersWsResponse.newBuilder();
    users.forEach(user -> {
      Permissions.User.Builder userResponse = response.addUsersBuilder()
        .setLogin(user.getLogin())
        .addAllPermissions(permissionsByUserId.get(user.getId()));
      ofNullable(user.getEmail()).ifPresent(userResponse::setEmail);
      ofNullable(emptyToNull(user.getEmail())).ifPresent(u -> userResponse.setAvatar(avatarResolver.create(user)));
      ofNullable(user.getName()).ifPresent(userResponse::setName);
    });

    response.getPagingBuilder()
      .setPageIndex(paging.pageIndex())
      .setPageSize(paging.pageSize())
      .setTotal(paging.total())
      .build();

    return response.build();
  }

  private List<UserDto> findUsers(DbSession dbSession, PermissionQuery query) {
    List<Integer> orderedIds = dbClient.userPermissionDao().selectUserIdsByQueryAndScope(dbSession, query);
    return Ordering.explicit(orderedIds).onResultOf(UserDto::getId).immutableSortedCopy(dbClient.userDao().selectByIds(dbSession, orderedIds));
  }

  private List<UserPermissionDto> findUserPermissions(DbSession dbSession, OrganizationDto org, List<UserDto> users, Optional<ProjectId> project) {
    if (users.isEmpty()) {
      return emptyList();
    }
    List<Integer> userIds = users.stream().map(UserDto::getId).collect(Collectors.toList());
    PermissionQuery.Builder queryBuilder = PermissionQuery.builder()
      .setOrganizationUuid(org.getUuid())
      .withAtLeastOnePermission();
    project.ifPresent(p -> queryBuilder.setComponent(p.getUuid(), p.getId()));
    return dbClient.userPermissionDao().selectUserPermissionsByQuery(dbSession, queryBuilder.build(), userIds);
  }
}
