package com.appsmith.server.services;

import com.appsmith.external.dtos.GitLogDTO;
import com.appsmith.external.git.GitExecutor;
import com.appsmith.git.service.GitExecutorImpl;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.constants.Entity;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.constants.SerialiseApplicationObjective;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationJson;
import com.appsmith.server.domains.GitApplicationMetadata;
import com.appsmith.server.domains.GitAuth;
import com.appsmith.server.domains.GitProfile;
import com.appsmith.server.domains.UserData;
import com.appsmith.server.dtos.GitBranchDTO;
import com.appsmith.server.dtos.GitCommitDTO;
import com.appsmith.server.dtos.GitConnectDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.CollectionUtils;
import com.appsmith.server.helpers.GitFileUtils;
import com.appsmith.server.solutions.ImportExportApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.util.StringUtils;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.appsmith.server.acl.AclPermission.MANAGE_APPLICATIONS;
import static com.appsmith.server.acl.AclPermission.READ_APPLICATIONS;

@Slf4j
@Service
@RequiredArgsConstructor
@Import({GitExecutorImpl.class})
public class GitServiceImpl implements GitService {

    private final UserService userService;
    private final UserDataService userDataService;
    private final SessionUserService sessionUserService;
    private final ApplicationService applicationService;
    private final ApplicationPageService applicationPageService;
    private final GitFileUtils fileUtils;
    private final ImportExportApplicationService importExportApplicationService;
    private final GitExecutor gitExecutor;

    private final static String DEFAULT_COMMIT_MESSAGE = "Appsmith default generated commit";

    @Override
    public Mono<Map<String, GitProfile>> updateOrCreateGitProfileForCurrentUser(GitProfile gitProfile, Boolean isDefault, String defaultApplicationId) {
        if(gitProfile.getAuthorName() == null || gitProfile.getAuthorName().length() == 0) {
            return Mono.error( new AppsmithException( AppsmithError.INVALID_PARAMETER, "Author Name"));
        }
        if(gitProfile.getAuthorEmail() == null || gitProfile.getAuthorEmail().length() == 0) {
            return Mono.error( new AppsmithException( AppsmithError.INVALID_PARAMETER, "Author Email"));
        }
        return sessionUserService.getCurrentUser()
                .flatMap(user -> userService.findByEmail(user.getEmail()))
                .flatMap(user -> userDataService
                        .getForUser(user.getId())
                        .flatMap(userData -> {
                            GitProfile userGitProfile = userData.getDefaultOrAppSpecificGitProfiles(defaultApplicationId);
                            /*
                             *  The gitProfiles will be null if the user has not created any git profile.
                             *  If null or if the request is to save the profile as default then we need to create this
                             *  field for the currentUser and save the profile data
                             *  Otherwise create a new entry or update existing entry
                             * */

                            if (gitProfile.equals(userGitProfile)) {
                                return Mono.just(userData);
                            } else if (userGitProfile == null || Boolean.TRUE.equals(isDefault) || StringUtils.isEmptyOrNull(defaultApplicationId)) {
                                // Assign the default config
                                userData.setDefaultGitProfile(gitProfile);
                            } else {
                                userData.getGitProfiles().put(defaultApplicationId, gitProfile);
                            }
                            UserData requiredUpdates = new UserData();
                            requiredUpdates.setGitProfiles(userData.getGitProfiles());
                            return userDataService.updateForUser(user, userData);
                        })
                        .map(UserData::getGitProfiles)
                );
    }

    @Override
    public Mono<Map<String, GitProfile>> updateOrCreateGitProfileForCurrentUser(GitProfile gitProfile) {
        return updateOrCreateGitProfileForCurrentUser(gitProfile, Boolean.TRUE, null);
    }

    @Override
    public Mono<GitProfile> getGitProfileForUser() {
        return getGitProfileForUser(null);
    }

    @Override
    public Mono<GitProfile> getGitProfileForUser(String defaultApplicationId) {
        return userDataService.getForCurrentUser()
                .map(userData -> {
                    if (userData.getDefaultOrAppSpecificGitProfiles(defaultApplicationId) == null) {
                        throw new AppsmithException(
                                AppsmithError.INVALID_GIT_CONFIGURATION, "Unable to find git author configuration for logged-in user." +
                                " You can set up a git profile from the user profile section."
                        );
                    }
                    return userData.getDefaultOrAppSpecificGitProfiles(defaultApplicationId);
                });
    }

    /**
     * This method will make a commit to local repo
     * @param commitDTO information required for making a commit
     * @param defaultApplicationId application branch on which the commit needs to be done
     * @return success message
     */
    @Override
    public Mono<String> commitApplication(GitCommitDTO commitDTO, String defaultApplicationId, MultiValueMap<String, String> params) {

        /*
        1. Check if application exists and user have sufficient permissions
        2. Check if branch name exists in git metadata
        3. Save application to the existing worktree (Directory for the specific branch)
        4. Commit application : git add, git commit (Also check if git init required)
         */
        String branchName = params.getFirst(FieldName.BRANCH_NAME);
        String commitMessage = commitDTO.getCommitMessage();
        StringBuilder result = new StringBuilder();

        if (commitMessage == null || commitMessage.isEmpty()) {
            commitDTO.setCommitMessage(DEFAULT_COMMIT_MESSAGE);
        }
        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }

        Mono<UserData> currentUserMono = userDataService.getForCurrentUser()
                .filter(userData -> !CollectionUtils.isNullOrEmpty(userData.getGitProfiles()))
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION,
                        "Unable to find git author configuration for logged-in user. You can set up a git profile from the user profile section."))
                );

        return applicationService.getApplicationByBranchNameAndDefaultApplication(branchName, defaultApplicationId, MANAGE_APPLICATIONS)
            .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.BRANCH_NAME, branchName)))
            .flatMap(childApplication -> publishAndOrGetApplication(childApplication.getId(), commitDTO.getDoPush()))
            .flatMap(childApplication -> {
                GitApplicationMetadata gitApplicationMetadata = childApplication.getGitApplicationMetadata();
                if (gitApplicationMetadata == null) {
                    throw new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, "Unable to find the git " +
                            "configuration, please configure the your application to use version control service");
                }
                String errorEntity = "";
                if (StringUtils.isEmptyOrNull(gitApplicationMetadata.getBranchName())) {
                    errorEntity = "branch name";
                } else if (StringUtils.isEmptyOrNull(gitApplicationMetadata.getDefaultApplicationId())) {
                    errorEntity = "default application";
                } else if (StringUtils.isEmptyOrNull(gitApplicationMetadata.getRepoName())) {
                    errorEntity = "repository name";
                }

                if (!errorEntity.isEmpty()) {
                    throw new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, "Unable to find " + errorEntity);
                }
                return Mono.zip(
                        importExportApplicationService
                            .exportApplicationById(childApplication.getId(), SerialiseApplicationObjective.VERSION_CONTROL),
                        Mono.just(childApplication)
                );
            })
            .flatMap(tuple -> {
                ApplicationJson applicationJson = tuple.getT1();
                Application childApplication = tuple.getT2();
                GitApplicationMetadata gitData = childApplication.getGitApplicationMetadata();
                Path baseRepoSuffix =
                        Paths.get(childApplication.getOrganizationId(), gitData.getDefaultApplicationId(), gitData.getRepoName());
                try {
                    return Mono.zip(
                            fileUtils.saveApplicationToLocalRepo(baseRepoSuffix, applicationJson, gitData.getBranchName()),
                            currentUserMono,
                            Mono.just(childApplication)
                    );
                } catch (IOException | GitAPIException e) {
                    log.error("Unable to open git directory, with error : ", e);
                    return Mono.error(new AppsmithException(AppsmithError.IO_ERROR, e.getMessage()));
                }
            })
            .map(tuple -> {
                Path baseRepoPath = tuple.getT1();
                Application childApplication = tuple.getT3();
                GitApplicationMetadata gitApplicationData = childApplication.getGitApplicationMetadata();
                GitProfile authorProfile =
                        tuple.getT2().getDefaultOrAppSpecificGitProfiles(gitApplicationData.getDefaultApplicationId());

                if (authorProfile == null) {
                    throw new AppsmithException(
                            AppsmithError.INVALID_GIT_CONFIGURATION, "Unable to find git author configuration for logged-in user." +
                            " You can set up a git profile from the user profile section."
                    );
                }
                    result.append("Commit Result : ");
                try {
                    result.append(gitExecutor.commitApplication(
                        baseRepoPath, commitMessage, authorProfile.getAuthorName(), authorProfile.getAuthorEmail())
                    );
                    return childApplication;
                } catch (IOException | GitAPIException e) {
                    log.error("git commit exception : ", e);
                    if (e instanceof EmptyCommitException) {
                        final String emptyCommitError = "On current branch nothing to commit, working tree clean";
                        if (Boolean.TRUE.equals(commitDTO.getDoPush())) {
                            result.append(emptyCommitError);
                            return childApplication;
                        }
                        throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "commit", emptyCommitError);
                    }
                    throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "commit", e.getMessage());
                }
            })
            .flatMap(childApplication -> {

                if (Boolean.TRUE.equals(commitDTO.getDoPush())) {
                    //push flow
                    result.append(". Push Result : ");
                    return pushApplication(childApplication.getId(), false)
                            .map(pushResult -> result.append(pushResult).toString());
                }
                return Mono.just(result.toString());
            });
    }

    /**
     * Method to get commit history for application branch
     * @param defaultApplicationId application for which the commit history is needed
     * @return list of commits
     */
    @Override
    public Mono<List<GitLogDTO>> getCommitHistory(String defaultApplicationId, MultiValueMap<String, String> params) {

        String branchName = params.getFirst(FieldName.BRANCH_NAME);

        return applicationService.getApplicationByBranchNameAndDefaultApplication(branchName, defaultApplicationId, READ_APPLICATIONS)
            .map(application -> {
                GitApplicationMetadata gitData = application.getGitApplicationMetadata();
                if ( gitData == null || StringUtils.isEmptyOrNull(application.getGitApplicationMetadata().getBranchName())) {

                    throw new AppsmithException(
                            AppsmithError.INVALID_GIT_CONFIGURATION,
                            "branch name is not available. Please reconfigure the application to connect to git repo"
                    );
                }
                try {
                    Path baseRepoSuffix = Paths.get(application.getOrganizationId(), gitData.getDefaultApplicationId(), gitData.getRepoName());
                    // Checkout to branch
                    gitExecutor.checkoutToBranch(baseRepoSuffix, gitData.getBranchName());
                    return gitExecutor.getCommitHistory(baseRepoSuffix);
                } catch (IOException | GitAPIException e) {
                    throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "log", e.getMessage());
                }
            });
    }

    /**
     *  Connect the application from Appsmith to a git repo
     *  This is the prerequisite step needed to perform all the git operation for an application
     *  We are implementing the deployKey approach and since the deploy-keys are repo level these keys are store under application.
     *  Each application is equal to a repo in the git(and each branch creates a new application with default application as parent)
     *  @param gitConnectDTO
     *            applicationId - this is used to link the local git repo to an application
     *            remoteUrl - used for connecting to remote repo etc
     *  @return Application object with the updated data
     * */
    @Override
    public Mono<Application> connectApplicationToGit(String defaultApplicationId, GitConnectDTO gitConnectDTO, String originHeader) {
        /*
         *  Connecting the application for the first time
         *  The ssh keys is already present in application object from the generate SSH key step
         *  We would be updating the remote url and default branchName
         * */

        if(StringUtils.isEmptyOrNull(gitConnectDTO.getRemoteUrl())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Remote Url"));
        }

        if (originHeader == null || originHeader.isBlank()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ORIGIN));
        }

        return updateOrCreateGitProfileForCurrentUser(
                gitConnectDTO.getGitProfile(), gitConnectDTO.isDefaultProfile(), defaultApplicationId)
                .then(getApplicationById(defaultApplicationId))
                .flatMap(application -> {
                    GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                        throw new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION);
                    } else {
                        String defaultBranch;
                        String repoName = getRepoName(gitConnectDTO.getRemoteUrl());
                        Path repoPath = Paths.get(application.getOrganizationId(), defaultApplicationId, repoName);
                        try {
                            defaultBranch = gitExecutor.cloneApplication(
                                    repoPath,
                                    gitConnectDTO.getRemoteUrl(),
                                    gitApplicationMetadata.getGitAuth().getPrivateKey(),
                                    gitApplicationMetadata.getGitAuth().getPublicKey()
                            );
                            if(!fileUtils.checkIfDirectoryIsEmpty(repoPath)) {
                                return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_REPO));
                            }
                        } catch (GitAPIException | IOException e) {
                            if (e instanceof TransportException) {
                                return Mono.error(new AppsmithException(AppsmithError.AUTHENTICATION_FAILURE, "SSH Key is not configured properly. Can you please try again by reconfiguring the SSH key"));
                            }
                            if (e instanceof InvalidRemoteException) {
                                return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "remote url"));
                            }
                            log.error("Error while cloning the remote repo, {}", e.getMessage());
                            return Mono.error(new AppsmithException(AppsmithError.INTERNAL_SERVER_ERROR));
                        }
                        return Mono.zip(Mono.just(application), Mono.just(defaultBranch), Mono.just(repoName));
                    }
                })
                .flatMap(tuple -> {
                    Application application = tuple.getT1();
                    String defaultBranch = tuple.getT2();
                    String repoName = tuple.getT3();

                    GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                    gitApplicationMetadata.setDefaultApplicationId(application.getId());
                    gitApplicationMetadata.setBranchName(defaultBranch);
                    gitApplicationMetadata.setRemoteUrl(gitConnectDTO.getRemoteUrl());
                    gitApplicationMetadata.setRepoName(repoName);
                    application.setGitApplicationMetadata(gitApplicationMetadata);
                    return applicationService.save(application);
                })
                .flatMap(application -> {
                    String repoName = getRepoName(gitConnectDTO.getRemoteUrl());
                    String defaultPageId = "";
                    if(application.getPages().isEmpty()) {
                        defaultPageId = application.getPages()
                                .stream()
                                .filter(applicationPage -> applicationPage.getIsDefault().equals(Boolean.TRUE))
                                .collect(Collectors.toList())
                                .get(0)
                                .getId();
                    } else {
                        // TODO either throw error message saying invalid application or have a default value
                        defaultPageId = "defaultPage";
                    }
                    String viewModeUrl = Paths.get("/", application.getId(),
                            Entity.APPLICATIONS, Entity.PAGES, defaultPageId).toString();
                    String editModeUrl = Paths.get(viewModeUrl, "edit").toString();
                    //Initialize the repo with readme file
                    try {
                        fileUtils.initializeGitRepo(
                                Paths.get(application.getOrganizationId(), defaultApplicationId, repoName, "README.md"),
                                originHeader + viewModeUrl,
                                originHeader + editModeUrl
                        );
                    } catch (IOException e) {
                        log.error("Error while cloning the remote repo, {}", e.getMessage());
                        return Mono.error(new AppsmithException(AppsmithError.INTERNAL_SERVER_ERROR));
                    }
                    return Mono.just(application);
                });
    }

    /**
     * Special chars are transformed to "-" : https://github.com/desktop/desktop/issues/3090
     * Sample repo urls :
     * git@github.com:username/reponame.git
     * ssh://git@bitbucket.org/<workspace_ID>/<repo_name>.git
     * @param remoteUrl ssh url of repo
     * @return repo name extracted from repo url
     */
    private String getRepoName(String remoteUrl) {
        // Pattern to match all words in the text
        final Matcher matcher = Pattern.compile("([^/]*).git$").matcher(remoteUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, "Remote URL is incorrect! Can you " +
                "please provide as per standard format => git@github.com:username/reponame.git");
    }

    @Override
    public Mono<String> pushApplication(String defaultApplicationId, MultiValueMap<String, String> params) {
        String branchName = params.getFirst(FieldName.BRANCH_NAME);
        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }
        return applicationService.getChildApplicationId(branchName, defaultApplicationId, MANAGE_APPLICATIONS)
            .switchIfEmpty(Mono.error(new AppsmithException(
                AppsmithError.NO_RESOURCE_FOUND, FieldName.APPLICATION, "for " + defaultApplicationId
            )))
            .flatMap(applicationId -> pushApplication(applicationId, true));
    }

    /**
     * Push flow for dehydrated apps
     * @param applicationId application which needs to be pushed to remote repo
     * @return Success message
     */
    private Mono<String> pushApplication(String applicationId, boolean doPublish) {

        return publishAndOrGetApplication(applicationId, doPublish)
                .flatMap(application -> {
                    if (applicationId.equals(application.getGitApplicationMetadata().getDefaultApplicationId())) {
                        return Mono.just(application);
                    }
                    return applicationService.findById(application.getGitApplicationMetadata().getDefaultApplicationId())
                        .map(defaultApp -> {
                            application.getGitApplicationMetadata().setGitAuth(defaultApp.getGitApplicationMetadata().getGitAuth());
                            return application;
                        });
                })
                .map(application -> {
                    GitApplicationMetadata gitData = application.getGitApplicationMetadata();

                    if (gitData == null
                            || StringUtils.isEmptyOrNull(gitData.getBranchName())
                            || StringUtils.isEmptyOrNull(gitData.getDefaultApplicationId())
                            || StringUtils.isEmptyOrNull(gitData.getGitAuth().getPrivateKey())) {

                        throw new AppsmithException(
                                AppsmithError.INVALID_GIT_CONFIGURATION,
                                "Please reconfigure the application to connect to git repo"
                        );
                    }
                    try {
                        Path baseRepoSuffix =
                                Paths.get(application.getOrganizationId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

                        GitAuth gitAuth = gitData.getGitAuth();
                        gitExecutor.checkoutToBranch(baseRepoSuffix, application.getGitApplicationMetadata().getBranchName());
                        return gitExecutor.pushApplication(
                            baseRepoSuffix, gitData.getRemoteUrl(), gitAuth.getPublicKey(), gitAuth.getPrivateKey()
                        );
                    } catch (IOException | GitAPIException | URISyntaxException e) {
                        throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "push", e.getMessage());
                    }
                });
    }

    /**
     * Disconnect from the git repo. This method will remove all the git metadata for the application
     * TODO Remove the files from the machine, since these files are stale
     * @param applicationId
     * @return Application data
     */
    @Override
    public Mono<Application> detachRemote(String applicationId) {
        return getApplicationById(applicationId)
                .flatMap(application -> {
                    if(Optional.ofNullable(application.getGitApplicationMetadata()).isEmpty()) {
                        return Mono.just(application);
                    }
                    //Remove the git contents from file system
                    GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                    String repoName = gitApplicationMetadata.getRepoName();
                    Path repoPath = Paths.get(application.getOrganizationId(), gitApplicationMetadata.getDefaultApplicationId(), repoName);
                    fileUtils.detachRemote(repoPath);

                    //Remove the git metadata from the db
                    return updateGitMetadata(applicationId, null);
                });
    }

    @Override
    public Mono<Application> updateGitMetadata(String applicationId, GitApplicationMetadata gitApplicationMetadata){

        // For default application we expect a GitAuth to be a part of gitMetadata. We are using save method to leverage
        // @Encrypted annotation used for private SSH keys
        return applicationService.findById(applicationId, AclPermission.MANAGE_APPLICATIONS)
                .flatMap(application -> {
                    application.setGitApplicationMetadata(gitApplicationMetadata);
                    return applicationService.save(application);
                })
                .flatMap(applicationService::setTransientFields);
    }

    public Mono<Application> createBranch(String defaultApplicationId, GitBranchDTO branchDTO, MultiValueMap<String, String> params) {

        /*
        1. Check if the src application is available and user have sufficient permissions
        2. Create and checkout to requested branch
        3. Rehydrate the application from source application reference
         */

        final String branch = params.getFirst(FieldName.BRANCH_NAME);
        if (StringUtils.isEmptyOrNull(branch)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }

        return applicationService.getApplicationByBranchNameAndDefaultApplication(branch, defaultApplicationId, MANAGE_APPLICATIONS)
            .flatMap(srcApplication -> {
                GitApplicationMetadata srcBranchGitData = srcApplication.getGitApplicationMetadata();
                if (srcBranchGitData == null || srcBranchGitData.getDefaultApplicationId() == null || srcBranchGitData.getRepoName() == null) {
                    throw new AppsmithException(
                            AppsmithError.INVALID_GIT_CONFIGURATION,
                            "Unable to find the parent branch. Please create a branch from other available branches"
                    );
                }
                Path repoSuffix =
                        Paths.get(srcApplication.getOrganizationId(), srcBranchGitData.getDefaultApplicationId(), srcBranchGitData.getRepoName());
                try {

                    // Create a new branch from the parent checked out branch
                    String branchName = gitExecutor.createAndCheckoutToBranch(repoSuffix, branchDTO.getBranchName());
                    String srcApplicationId = srcApplication.getId();
                    srcBranchGitData.setBranchName(branchName);
                    // Save a new application in DB and update from the parent branch application
                    srcBranchGitData.setGitAuth(null);
                    srcApplication.setId(null);
                    srcApplication.setPages(null);
                    srcApplication.setPublishedPages(null);
                    srcApplication.setGitApplicationMetadata(srcBranchGitData);
                    return Mono.zip(
                        applicationService.save(srcApplication),
                        importExportApplicationService.exportApplicationById(srcApplicationId, SerialiseApplicationObjective.VERSION_CONTROL)
                    );
                } catch (IOException | GitAPIException e) {
                    throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "branch", e.getMessage());
                }
            })
            .flatMap(tuple -> {
                Application savedApplication = tuple.getT1();
                return importExportApplicationService.importApplicationInOrganization(
                    savedApplication.getOrganizationId(),
                    tuple.getT2(),
                    savedApplication.getId()
                );
            });
    }

    public Mono<Application> checkoutBranch(String defaultApplicationId, String branchName) {

        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }
        return getApplicationById(defaultApplicationId)
            .flatMap(application -> {
                if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                    throw new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION);
                }
                return applicationService.getApplicationByBranchNameAndDefaultApplication(
                    branchName, defaultApplicationId, READ_APPLICATIONS
                );
            });
    }

    private Mono<Application> publishAndOrGetApplication(String applicationId, boolean publish) {
        if (Boolean.TRUE.equals(publish)) {
            return applicationPageService.publish(applicationId, true)
                // Get application here to decrypt the git private key if present
                .then(getApplicationById(applicationId));
        }
        return getApplicationById(applicationId);
    }

    Mono<Application> getApplicationById(String applicationId) {
        return applicationService.findById(applicationId, AclPermission.MANAGE_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.APPLICATION_ID, applicationId)));
    }

    /**
     * We assume that the repo already exists via the connect or commit api
     * @param applicationId application for which we want to pull remote changes and merge
     * @param branchName remoteBranch from which the changes will be pulled and merged
     * @return return the status of pull operation
     */
    @Override
    public Mono<Object> pullApplication(String applicationId, String branchName) {
        /*
         * 1.Rehydrate the application from Mongodb to make sure that the file system has latest application data from mongodb
         * 2.Do git pull after the rehydration and merge the remote changes to the current branch
         * 3.Then rehydrate the from the file system to mongodb so that the latest changes from remote are rendered to the application
         * 4.Get the latest application mono from the mongodb and send it back to client
         * */
        return  applicationService.getApplicationByBranchNameAndDefaultApplication(branchName, applicationId, MANAGE_APPLICATIONS)
                .zipWith(importExportApplicationService.exportApplicationById(applicationId,SerialiseApplicationObjective.VERSION_CONTROL))
                .flatMap(tuple -> {
                    Application application = tuple.getT1();
                    ApplicationJson applicationJson = tuple.getT2();

                    GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                        throw new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION);
                    }
                    Path repoPath = Paths.get(application.getOrganizationId(),
                            gitApplicationMetadata.getDefaultApplicationId(),
                            gitApplicationMetadata.getRepoName());

                    // 1. Rehydrate application from db
                    try {
                        return fileUtils.saveApplicationToLocalRepo(repoPath, applicationJson, branchName)
                                .zipWith(Mono.just(application));
                    } catch (IOException | GitAPIException e) {
                        throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", e.getMessage());
                    }
                })
                .flatMap( applicationTuple-> {
                    try {
                        Path repoPath = applicationTuple.getT1();
                        Application application = applicationTuple.getT2();
                        GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();

                        //2. git pull origin branchName
                        String status = gitExecutor.pullApplication(
                                repoPath,
                                gitApplicationMetadata.getRemoteUrl(),
                                gitApplicationMetadata.getBranchName(),
                                gitApplicationMetadata.getGitAuth().getPrivateKey(),
                                gitApplicationMetadata.getGitAuth().getPublicKey());

                        //3. Hydrate from file system to db
                        ApplicationJson applicationJson = fileUtils.reconstructApplicationFromGitRepo(
                                application.getOrganizationId(),
                                gitApplicationMetadata.getDefaultApplicationId(),
                                branchName);

                        //4. Get the latest application mono with all the changes
                        return importExportApplicationService.importApplicationInOrganization(application.getOrganizationId(), applicationJson, applicationId);
                    } catch (IOException | GitAPIException e) {
                        if (e.getMessage().contains("Nothing to fetch.")) {
                            return Mono.just("Nothing to fetch from remote. All changes are upto date.");
                        } else {
                            throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "pull", e.getMessage());
                        }
                    }
                });
    }

    @Override
    public Mono<List<String>> listBranchForApplication(String defaultApplicationId) {
        return getApplicationById(defaultApplicationId)
            .flatMap(application -> {
                GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                if (gitApplicationMetadata == null || gitApplicationMetadata.getDefaultApplicationId() == null) {
                    return Mono.error(new AppsmithException(
                        AppsmithError.INVALID_GIT_CONFIGURATION,
                        "Can't find root application. Please configure the application with git"));
                }
                Path repoPath = Paths.get(application.getOrganizationId(),
                        gitApplicationMetadata.getDefaultApplicationId(),
                        gitApplicationMetadata.getRepoName());
                try {
                    return Mono.just(gitExecutor.getBranches(repoPath));
                } catch (IOException | GitAPIException e) {
                    return Mono.error(new AppsmithException(
                        AppsmithError.GIT_ACTION_FAILED,
                        "branch --list",
                        "Error while accessing the file system. Details :" + e.getMessage()));
                }
            });
    }

    @Override
    public Mono<GitApplicationMetadata> getGitApplicationMetadata(String defaultApplicationId) {
        return getApplicationById(defaultApplicationId)
            .flatMap(application -> {
                GitApplicationMetadata gitData = application.getGitApplicationMetadata();
                if (gitData == null) {
                    return Mono.empty();
                }

                if (!defaultApplicationId.equals(gitData.getDefaultApplicationId())) {
                    throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "defaultApplicationId");
                } else if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                    throw new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION);
                }

                return userDataService.getForCurrentUser()
                    .map(userData -> {
                        Map<String, GitProfile> gitProfiles = new HashMap<>();
                        if (!CollectionUtils.isNullOrEmpty(userData.getGitProfiles())) {
                            gitProfiles.put(FieldName.DEFAULT_GIT_PROFILE, userData.getDefaultOrAppSpecificGitProfiles(null));
                            gitProfiles.put(defaultApplicationId, userData.getDefaultOrAppSpecificGitProfiles(defaultApplicationId));
                        }
                        gitData.setGitProfiles(gitProfiles);
                        if (gitData.getGitAuth() != null) {
                            gitData.setPublicKey(gitData.getGitAuth().getPublicKey());
                        }
                        return gitData;
                    });
            });
    }

    /**
     * Get the status of the mentioned branch
     *
     * @param defaultApplicationId root/default application
     * @param params contains the branch name
     * @return Map of json file names which are added, modified, conflicting, removed and the working tree if this is clean
     */
    public Mono<Map<String, Object>> getStatus(String defaultApplicationId, MultiValueMap<String, String> params) {

        /*
            1. Copy resources from DB to local repo
            2. Fetch the current status from local repo
         */
        String branchName = params.getFirst(FieldName.BRANCH_NAME);
        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }

        return applicationService.getApplicationByBranchNameAndDefaultApplication(branchName, defaultApplicationId, MANAGE_APPLICATIONS)
                .zipWhen(application -> importExportApplicationService.exportApplicationById(application.getId(), SerialiseApplicationObjective.VERSION_CONTROL))
                .flatMap(tuple -> {
                    Application application = tuple.getT1();
                    ApplicationJson applicationJson = tuple.getT2();
                    GitApplicationMetadata gitData = application.getGitApplicationMetadata();
                    Path repoSuffix =
                            Paths.get(application.getOrganizationId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

                    try {
                        return fileUtils.saveApplicationToLocalRepo(repoSuffix, applicationJson, branchName);
                    } catch (IOException | GitAPIException e) {
                        throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", e.getMessage());
                    }
                })
                .map(repoPath -> {
                    try {
                        return gitExecutor.getStatus(repoPath, branchName);
                    } catch (GitAPIException | IOException e) {
                        throw new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", e.getMessage());
                    }
                });
    }

    @Override
    public Mono<String> mergeBranch(String applicationId, String sourceBranch, String destinationBranch) {
        return getApplicationById(applicationId)
                .flatMap(application -> {
                    GitApplicationMetadata gitApplicationMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                        throw new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION);
                    }
                    Path repoPath = Paths.get(application.getOrganizationId(),
                            gitApplicationMetadata.getDefaultApplicationId(),
                            gitApplicationMetadata.getRepoName());
                    try {
                        String message = gitExecutor.mergeBranch(repoPath, sourceBranch, destinationBranch);
                        return Mono.just(message);
                    } catch (IOException e) {
                        return Mono.error(new AppsmithException(AppsmithError.INTERNAL_SERVER_ERROR));
                    }
                });
    }

    private boolean isInvalidDefaultApplicationGitMetadata(GitApplicationMetadata gitApplicationMetadata) {
        if (Optional.ofNullable(gitApplicationMetadata).isEmpty()
                || Optional.ofNullable(gitApplicationMetadata.getGitAuth()).isEmpty()
                || StringUtils.isEmptyOrNull(gitApplicationMetadata.getGitAuth().getPrivateKey())
                || StringUtils.isEmptyOrNull(gitApplicationMetadata.getGitAuth().getPublicKey())) {
            return true;
        }
        return false;
    }
}
