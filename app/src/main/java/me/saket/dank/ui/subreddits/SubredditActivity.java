package me.saket.dank.ui.subreddits;

import static io.reactivex.android.schedulers.AndroidSchedulers.mainThread;
import static me.saket.dank.di.Dank.subscriptionManager;
import static me.saket.dank.utils.CommonUtils.defaultIfNull;
import static me.saket.dank.utils.RxUtils.applySchedulers;
import static me.saket.dank.utils.RxUtils.applySchedulersCompletable;
import static me.saket.dank.utils.RxUtils.applySchedulersSingle;
import static me.saket.dank.utils.RxUtils.doNothing;
import static me.saket.dank.utils.RxUtils.doOnSingleStartAndTerminate;
import static me.saket.dank.utils.RxUtils.logError;
import static me.saket.dank.utils.Views.setMarginTop;
import static me.saket.dank.utils.Views.setPaddingTop;
import static me.saket.dank.utils.Views.statusBarHeight;
import static me.saket.dank.utils.Views.touchLiesOn;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.NavUtils;
import android.support.v7.widget.DefaultItemAnimator;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.github.zagum.expandicon.ExpandIconView;
import com.jakewharton.rxrelay2.BehaviorRelay;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.SingleTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import java.util.HashSet;
import java.util.Set;
import me.saket.dank.R;
import me.saket.dank.data.DankRedditClient;
import me.saket.dank.data.RedditLink;
import me.saket.dank.data.ResolvedError;
import me.saket.dank.di.Dank;
import me.saket.dank.notifs.CheckUnreadMessagesJobService;
import me.saket.dank.ui.DankPullCollapsibleActivity;
import me.saket.dank.ui.authentication.LoginActivity;
import me.saket.dank.ui.preferences.UserPreferencesActivity;
import me.saket.dank.ui.submission.CachedSubmissionFolder;
import me.saket.dank.ui.submission.SubmissionFragment;
import me.saket.dank.ui.subreddits.SubmissionsAdapter.SubmissionViewHolder;
import me.saket.dank.utils.DankSubmissionRequest;
import me.saket.dank.utils.InfiniteScrollListener;
import me.saket.dank.utils.InfiniteScrollRecyclerAdapter;
import me.saket.dank.utils.InfiniteScrollRecyclerAdapter.HeaderFooterInfo;
import me.saket.dank.utils.Keyboards;
import me.saket.dank.utils.RecyclerViewArrayAdapter;
import me.saket.dank.widgets.DankToolbar;
import me.saket.dank.widgets.EmptyStateView;
import me.saket.dank.widgets.ErrorStateView;
import me.saket.dank.widgets.InboxUI.ExpandablePageLayout;
import me.saket.dank.widgets.InboxUI.InboxRecyclerView;
import me.saket.dank.widgets.InboxUI.IndependentExpandablePageLayout;
import me.saket.dank.widgets.ToolbarExpandableSheet;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.Subreddit;
import net.dean.jraw.paginators.Sorting;
import net.dean.jraw.paginators.TimePeriod;
import timber.log.Timber;

public class SubredditActivity extends DankPullCollapsibleActivity implements SubmissionFragment.Callbacks, NewSubredditSubscriptionDialog.Callback {

  private static final int REQUEST_CODE_LOGIN = 100;
  protected static final String KEY_INITIAL_SUBREDDIT_LINK = "initialSubredditLink";
  private static final String KEY_ACTIVE_SUBREDDIT = "activeSubreddit";
  private static final String KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE = "isSubredditPickerVisible";
  private static final String KEY_IS_USER_PROFILE_SHEET_VISIBLE = "isUserProfileSheetVisible";

  @BindView(R.id.subreddit_root) IndependentExpandablePageLayout contentPage;
  @BindView(R.id.toolbar) DankToolbar toolbar;
  @BindView(R.id.subreddit_toolbar_close) View toolbarCloseButton;
  @BindView(R.id.subreddit_toolbar_title) TextView toolbarTitleView;
  @BindView(R.id.subreddit_toolbar_title_arrow) ExpandIconView toolbarTitleArrowView;
  @BindView(R.id.subreddit_toolbar_title_container) ViewGroup toolbarTitleContainer;
  @BindView(R.id.subreddit_toolbar_container) ViewGroup toolbarContainer;
  @BindView(R.id.subreddit_submission_list) InboxRecyclerView submissionList;
  @BindView(R.id.subreddit_submission_page) ExpandablePageLayout submissionPage;
  @BindView(R.id.subreddit_toolbar_expandable_sheet) ToolbarExpandableSheet toolbarSheet;
  @BindView(R.id.subreddit_progress) View firstLoadProgressView;
  @BindView(R.id.subreddit_submission_emptyState) EmptyStateView emptyStateView;
  @BindView(R.id.subreddit_submission_errorState) ErrorStateView firstLoadErrorStateView;

  private SubmissionFragment submissionFragment;
  private InfiniteScrollRecyclerAdapter<Submission, ?> submissionAdapterWithProgress;
  private BehaviorRelay<String> subredditNameChangesRelay = BehaviorRelay.create();
  private Set<CachedSubmissionFolder> firstRefreshDoneForSubredditFolders = new HashSet<>();
  private Disposable ongoingSubmissionsLoadDisposable = Disposables.disposed();

  protected static void addStartExtrasToIntent(RedditLink.Subreddit subredditLink, @Nullable Rect expandFromShape, Intent intent) {
    intent.putExtra(KEY_INITIAL_SUBREDDIT_LINK, subredditLink);
    intent.putExtra(KEY_EXPAND_FROM_SHAPE, expandFromShape);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    boolean isPullCollapsible = !isTaskRoot();
    setPullToCollapseEnabled(isPullCollapsible);

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_subreddit);
    ButterKnife.bind(this);
    setupContentExpandablePage(contentPage);

    // Add top-margin to make room for the status bar.
    int statusBarHeight = statusBarHeight(getResources());
    setPaddingTop(toolbar, statusBarHeight);
    setMarginTop(toolbarTitleContainer, statusBarHeight);
    setMarginTop(submissionList, statusBarHeight);
    setPaddingTop(toolbarSheet, statusBarHeight);

    findAndSetupToolbar();
    getSupportActionBar().setDisplayShowTitleEnabled(false);
    subredditNameChangesRelay.subscribe(subredditName -> setTitle(subredditName));

    if (isPullCollapsible) {
      contentPage.setNestedExpandablePage(submissionPage);
      expandFrom(getIntent().getParcelableExtra(KEY_EXPAND_FROM_SHAPE));

      toolbarCloseButton.setVisibility(View.VISIBLE);
      toolbarCloseButton.setOnClickListener(__ -> NavUtils.navigateUpFromSameTask(this));
    }
  }

  @Override
  protected void onPostCreate(@Nullable Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);

    setupSubmissionList();

    // Get frontpage (or retained subreddit's) submissions.
    if (savedInstanceState != null) {
      String retainedSub = savedInstanceState.getString(KEY_ACTIVE_SUBREDDIT);
      //noinspection ConstantConditions
      subredditNameChangesRelay.accept(retainedSub);

    } else if (getIntent().hasExtra(KEY_INITIAL_SUBREDDIT_LINK)) {
      String requestedSub = ((RedditLink.Subreddit) getIntent().getSerializableExtra(KEY_INITIAL_SUBREDDIT_LINK)).name;
      subredditNameChangesRelay.accept(requestedSub);

    } else {
      subredditNameChangesRelay.accept(Dank.subscriptionManager().defaultSubreddit());
    }

    if (savedInstanceState != null) {
      submissionList.handleOnRestoreInstanceState(savedInstanceState);
    }

    // Setup submission page.
    submissionFragment = (SubmissionFragment) getSupportFragmentManager().findFragmentById(submissionPage.getId());
    if (submissionFragment == null) {
      submissionFragment = SubmissionFragment.create();
    }
    getSupportFragmentManager()
        .beginTransaction()
        .replace(submissionPage.getId(), submissionFragment)
        .commit();

    contentPage.setPullToCollapseIntercepter((event, downX, downY, upwardPagePull) -> {
      if (touchLiesOn(toolbarContainer, downX, downY)) {
        if (touchLiesOn(toolbarSheet, downX, downY) && isSubredditPickerVisible()) {
          boolean intercepted = findSubredditPickerSheet().shouldInterceptPullToCollapse(downX, downY);
          if (intercepted) {
            return true;
          }
        }
        return false;
      }
      //noinspection SimplifiableIfStatement
      if (touchLiesOn(submissionList, downX, downY)) {
        return submissionList.canScrollVertically(upwardPagePull ? 1 : -1);
      }
      return false;
    });

    setupToolbarSheet();

    // Schedule subreddit syncs + do an immediate sync.
    if (isTaskRoot()) {
      // TODO: Also subscribe to on-logged-in-event and run these jobs:
      unsubscribeOnDestroy(Dank.reddit()
          .isUserLoggedIn()
          .observeOn(mainThread())
          .subscribe(loggedIn -> {
            if (loggedIn) {
              SubredditSubscriptionsSyncJob.syncImmediately(this);
              SubredditSubscriptionsSyncJob.schedule(this);
              CheckUnreadMessagesJobService.syncImmediately(this);
              CheckUnreadMessagesJobService.schedule(this);

            } else {
              Timber.i("Couldn't start snyc. Is logged in? %s", loggedIn);
            }
          })
      );
    }

    // Restore state of subreddit picker sheet / user profile sheet.
    if (savedInstanceState != null) {
      if (savedInstanceState.getBoolean(KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE)) {
        showSubredditPickerSheet();
      } else if (savedInstanceState.getBoolean(KEY_IS_USER_PROFILE_SHEET_VISIBLE)) {
        showUserProfileSheet();
      }
    }

    emptyStateView.setEmoji(R.string.subreddit_empty_state_title);
    emptyStateView.setMessage(R.string.subreddit_empty_state_message);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    submissionList.handleOnSaveInstance(outState);
    if (subredditNameChangesRelay.hasValue()) {
      outState.putString(KEY_ACTIVE_SUBREDDIT, subredditNameChangesRelay.getValue());
    }
    outState.putBoolean(KEY_IS_SUBREDDIT_PICKER_SHEET_VISIBLE, isSubredditPickerVisible());
    outState.putBoolean(KEY_IS_USER_PROFILE_SHEET_VISIBLE, isUserProfileSheetVisible());
    super.onSaveInstanceState(outState);
  }

  @Override
  public void setTitle(CharSequence subredditName) {
    boolean isFrontpage = subscriptionManager().isFrontpage(subredditName.toString());
    toolbarTitleView.setText(isFrontpage ? getString(R.string.app_name) : subredditName);
  }

  private void setupToolbarSheet() {
    toolbarSheet.hideOnOutsideClick(submissionList);
    toolbarSheet.setStateChangeListener(state -> {
      switch (state) {
        case EXPANDING:
          if (isSubredditPickerVisible()) {
            // When subreddit picker is showing, we'll show a "configure subreddits" button in the toolbar.
            invalidateOptionsMenu();

          } else if (isUserProfileSheetVisible()) {
            setTitle(getString(R.string.user_name_u_prefix, Dank.reddit().loggedInUserName()));
          }

          toolbarTitleArrowView.setState(ExpandIconView.LESS, true);
          break;

        case EXPANDED:
          break;

        case COLLAPSING:
          if (isSubredditPickerVisible()) {
            Keyboards.hide(this, toolbarSheet);

          } else if (isUserProfileSheetVisible()) {
            // This will update the title.
            subredditNameChangesRelay.accept(subredditNameChangesRelay.getValue());
          }
          toolbarTitleArrowView.setState(ExpandIconView.MORE, true);
          break;

        case COLLAPSED:
          toolbarSheet.removeAllViews();
          toolbarSheet.collapse();
          break;
      }
    });
  }

// ======== SUBMISSIONS ======== //

  private void setupSubmissionList() {
    submissionList.setLayoutManager(submissionList.createLayoutManager());
    submissionList.setItemAnimator(new DefaultItemAnimator());
    submissionList.setExpandablePage(submissionPage, toolbarContainer);

    SubmissionsAdapter submissionsAdapter = new SubmissionsAdapter(Dank.voting(), Dank.userPrefs());
    submissionsAdapter.setOnItemClickListener((submission, submissionItemView, submissionId) -> {
      DankSubmissionRequest submissionRequest = DankSubmissionRequest.builder(submission.getId())
          .commentSort(defaultIfNull(submission.getSuggestedSort(), DankRedditClient.DEFAULT_COMMENT_SORT))
          .build();
      submissionFragment.populateUi(submission, submissionRequest);

      submissionPage.post(() -> {
        // Posing the expand() call to the page's message queue seems to result in a smoother
        // expand animation. I assume this is because the expand() call only executes once the
        // page is done updating its views for this new submission. This might be a placebo too
        // and without enough testing I cannot be sure about this.
        submissionList.expandItem(submissionList.indexOfChild(submissionItemView), submissionId);
      });
    });

    // Wrapper adapter for swipe gestures.
    SwipeableSubmissionHelper swipeActionsManager = new SwipeableSubmissionHelper(Dank.submissions(), Dank.voting());
    RecyclerViewArrayAdapter<Submission, SubmissionViewHolder> swipeableSubmissionsAdapter = swipeActionsManager.wrapAdapter(submissionsAdapter);
    swipeActionsManager.attachToRecyclerView(submissionList);

    // Wrapper adapter for infinite scroll progress and errors.
    submissionAdapterWithProgress = InfiniteScrollRecyclerAdapter.wrap(swipeableSubmissionsAdapter);
    submissionList.setAdapter(submissionAdapterWithProgress);

    unsubscribeOnDestroy(
        subredditNameChangesRelay
            .observeOn(AndroidSchedulers.mainThread())
            .map(subredditName -> CachedSubmissionFolder.create(subredditName, Sorting.HOT, TimePeriod.DAY))
            .doOnNext(folder -> Timber.i("Sub change: %s", folder.subredditName()))
            .subscribe(folder -> loadSubmissions(folder))
    );
  }

  private void loadSubmissions(CachedSubmissionFolder folder) {
    Completable refreshBeforeLoadCompletable;
    boolean isFirstRefresh = !firstRefreshDoneForSubredditFolders.contains(folder);
    if (isFirstRefresh) {
      refreshBeforeLoadCompletable = Dank.submissions().fetchAndSaveFromRemote(folder, true /* removeExisting */)
          .toCompletable()
          .compose(applySchedulersCompletable())
          .doOnSubscribe(o -> Timber.i("Refreshing from remote"))
          .doOnComplete(() -> firstRefreshDoneForSubredditFolders.add(folder));
    } else {
      refreshBeforeLoadCompletable = Completable.complete();
    }

    // Unfortunately, using switchMap is not an option here so disposing old chains manually.
    ongoingSubmissionsLoadDisposable.dispose();

    ongoingSubmissionsLoadDisposable = refreshBeforeLoadCompletable
        .doOnSubscribe(o -> {
          firstLoadErrorStateView.setVisibility(View.GONE);
          firstLoadProgressView.setVisibility(View.VISIBLE);
          submissionAdapterWithProgress.accept(null);
        })
        .onErrorResumeNext(swallowErrorUnlessCacheIsEmpty(folder))
        .doOnTerminate(() -> firstLoadProgressView.setVisibility(View.GONE))
        .andThen(Dank.submissions().submissions(folder))
        .compose(applySchedulers())
        .doOnNext(submissions -> Timber.d("Fetched %s submissions from DB under %s", submissions.size(), subredditNameChangesRelay.getValue()))
        .subscribe(submissionAdapterWithProgress, logError("Couldn't get messages"));
    unsubscribeOnDestroy(ongoingSubmissionsLoadDisposable);
  }

  private Function<Throwable, CompletableSource> swallowErrorUnlessCacheIsEmpty(CachedSubmissionFolder folder) {
    return error -> {
      // If any error occurs, swallow it unless we don't have anything in the DB to show.
      return Dank.submissions().hasSubmissions(folder)
          .compose(applySchedulersSingle())
          .doOnSubscribe(o -> firstLoadErrorStateView.setVisibility(View.GONE))
          .flatMapCompletable(hasExistingSubmissions -> {
            if (hasExistingSubmissions) {
              return Completable.complete();

            } else {
              ResolvedError resolvedError = Dank.errors().resolve(error);
              firstLoadErrorStateView.applyFrom(resolvedError);
              firstLoadErrorStateView.setVisibility(View.VISIBLE);
              firstLoadErrorStateView.setOnRetryClickListener(o -> loadSubmissions(folder));
              Timber.w("Error: %s", error.getMessage());
              if (resolvedError.isUnknown()) {
                Timber.e(error, "Unknown error while refreshing submissions");
              }
              return Completable.error(error);
            }
          });
    };
  }

  // TODO: Reset on subreddit change, because we suspend infinite scroll if no more submissions were fetched.
  // TODO: This might already be happening?
  private void startInfiniteScroll(boolean isRetrying) {
    InfiniteScrollListener scrollListener = InfiniteScrollListener.create(submissionList, InfiniteScrollListener.DEFAULT_LOAD_THRESHOLD);
    scrollListener.setEmitInitialEvent(isRetrying);

    CachedSubmissionFolder folder = CachedSubmissionFolder.create(subredditNameChangesRelay.getValue(), Sorting.HOT, TimePeriod.DAY);
    unsubscribeOnDestroy(scrollListener.emitWhenLoadNeeded()
        .doOnNext(o -> Timber.i("need to load more"))
        .flatMapSingle(o -> Dank.submissions().fetchAndSaveMoreSubmissions(folder)
            .compose(applySchedulersSingle())
            .compose(handleProgressAndErrorForLoadMore())
            .compose(doOnSingleStartAndTerminate(ongoing -> scrollListener.setLoadOngoing(ongoing)))
        )
        .takeUntil(fetchedMessages -> (boolean) fetchedMessages.isEmpty())
        .subscribe(doNothing(), doNothing()));
  }

  private <T> SingleTransformer<T, T> handleProgressAndErrorForLoadMore() {
    return upstream -> upstream
        .doOnSubscribe(o -> submissionAdapterWithProgress.setFooter(HeaderFooterInfo.createFooterProgress()))
        .doOnSuccess(o -> submissionAdapterWithProgress.setFooter(HeaderFooterInfo.createHidden()))
        .doOnError(error -> submissionAdapterWithProgress.setFooter(HeaderFooterInfo.createError(
            R.string.subreddit_error_failed_to_load_more_submissions,
            o -> startInfiniteScroll(true /* isRetrying */)
        )));
  }

  private void onClickRefresh() {
    CachedSubmissionFolder activeFolder = CachedSubmissionFolder.create(subredditNameChangesRelay.getValue(), Sorting.HOT, TimePeriod.DAY);

    // This will force loadSubmissions() to get re-called.
    unsubscribeOnDestroy(Dank.submissions().removeAllCachedInFolder(activeFolder)
        .subscribe(() -> {
          firstRefreshDoneForSubredditFolders.remove(activeFolder);
          subredditNameChangesRelay.accept(subredditNameChangesRelay.getValue());
        }));
  }

// ======== NAVIGATION ======== //

  @Override
  public void onClickSubmissionToolbarUp() {
    submissionList.collapse();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_subreddit, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_refresh_submissions:
        onClickRefresh();
        return true;

      case R.id.action_user_profile:
        unsubscribeOnDestroy(Dank.reddit()
            .isUserLoggedIn()
            .observeOn(mainThread())
            .subscribe(loggedIn -> {
              if (loggedIn) {
                showUserProfileSheet();
              } else {
                LoginActivity.startForResult(this, REQUEST_CODE_LOGIN);
              }
            })
        );
        return true;

      case R.id.action_preferences:
        UserPreferencesActivity.start(this);
        return true;

      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @OnClick(R.id.subreddit_toolbar_title)
  void onClickToolbarTitle() {
    if (isUserProfileSheetVisible() || isSubredditPickerVisible()) {
      toolbarSheet.collapse();
    } else {
      showSubredditPickerSheet();
    }
  }

// ======== SUBREDDIT PICKER SHEET ======== //

  void showSubredditPickerSheet() {
    SubredditPickerSheetView pickerSheet = SubredditPickerSheetView.showIn(toolbarSheet, contentPage);
    pickerSheet.post(() -> toolbarSheet.expand());

    pickerSheet.setCallbacks(new SubredditPickerSheetView.Callbacks() {
      @Override
      public void onSelectSubreddit(String subredditName) {
        toolbarSheet.collapse();
        if (!subredditName.equalsIgnoreCase(subredditNameChangesRelay.getValue())) {
          subredditNameChangesRelay.accept(subredditName);
        }
      }

      @Override
      public void onClickAddNewSubreddit() {
        NewSubredditSubscriptionDialog.show(getSupportFragmentManager());
      }

      @Override
      public void onSubredditsChanged() {
        // Refresh the submissions if the frontpage was active.
        if (Dank.subscriptionManager().isFrontpage(subredditNameChangesRelay.getValue())) {
          subredditNameChangesRelay.accept(subredditNameChangesRelay.getValue());
        }
      }
    });
  }

  @Override
  public void onEnterNewSubredditForSubscription(Subreddit newSubreddit) {
    if (isSubredditPickerVisible()) {
      findSubredditPickerSheet().subscribeTo(newSubreddit);
    }
  }

  /**
   * Whether the subreddit picker is visible, albeit partially.
   */
  private boolean isSubredditPickerVisible() {
    return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof SubredditPickerSheetView;
  }

  private SubredditPickerSheetView findSubredditPickerSheet() {
    return ((SubredditPickerSheetView) toolbarSheet.getChildAt(0));
  }

// ======== USER PROFILE SHEET ======== //

  void showUserProfileSheet() {
    UserProfileSheetView pickerSheet = UserProfileSheetView.showIn(toolbarSheet);
    pickerSheet.post(() -> toolbarSheet.expand());
  }

  private boolean isUserProfileSheetVisible() {
    return !toolbarSheet.isCollapsed() && toolbarSheet.getChildAt(0) instanceof UserProfileSheetView;
  }

  @Override
  public void onBackPressed() {
    if (submissionPage.isExpandedOrExpanding()) {
      submissionList.collapse();

    } else if (!toolbarSheet.isCollapsed()) {
      toolbarSheet.collapse();

    } else {
      super.onBackPressed();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_CODE_LOGIN && resultCode == RESULT_OK) {
      // Show user's profile.
      showUserProfileSheet();

      // Reload submissions if we're on the frontpage because the frontpage
      // submissions will change if the subscriptions change.
      if (Dank.subscriptionManager().isFrontpage(subredditNameChangesRelay.getValue())) {
        subredditNameChangesRelay.accept(subredditNameChangesRelay.getValue());
      }

      firstRefreshDoneForSubredditFolders.clear();

      // Reload subreddit subscriptions. Not implementing onError() is intentional.
      // This code is not supposed to fail :/
      Dank.subscriptionManager().removeAll()
          .andThen(Dank.submissions().removeAllCached())
          .subscribeOn(Schedulers.io())
          .subscribe();

      // TODO: Expose a callback when the user logs in. Get subreddits, messages and profile.

    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }
}
