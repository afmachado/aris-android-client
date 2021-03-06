package edu.uoregon.casls.aris_android.services;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.BinaryHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.apache.http.Header;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.uoregon.casls.aris_android.GamePlayActivity;
import edu.uoregon.casls.aris_android.R;
import edu.uoregon.casls.aris_android.Utilities.AppConfig;
import edu.uoregon.casls.aris_android.Utilities.AppUtils;
import edu.uoregon.casls.aris_android.data_objects.Media;
import edu.uoregon.casls.aris_android.data_objects.cd_data_objects.MediaCD;

/*
* Created by smorison on 11/18/15.
*/
public class ARISMediaLoader {

	private static final int LOAD_MEDIA_AT_ONCE = 5;
	private int mediaDataLoaded = 0;

	public Map<String, MediaResult> dataConnections = new LinkedHashMap<>(); //NSMutableDictionary dataConnections;
	public List<MediaResult>        metaConnections = new ArrayList<>();  //NSMutableArray metaConnections;

	public transient GamePlayActivity mGamePlayAct;
	private boolean preloadingToFile = false;
	private boolean mediaPreloadInProgress = false;

	public ARISMediaLoader(GamePlayActivity gamePlayActivity) {
		this.initContext(gamePlayActivity);
	}

	private void initContext(GamePlayActivity gamePlayActivity) {
		mGamePlayAct = gamePlayActivity;
	}

	//	public void loadMedia(Media m, ARISDelegateHandle dh) {
	public void loadMedia(Media m) { // media base values not getting set. mediaCD =is= though. This is by design. See Phil's comments at bottom of this file
		if (m == null) return;

		MediaResult mr = new MediaResult();
		mr.media = m;
//		mr.delegateHandles.add(dh); // Android: avoiding this concept

		this.loadMediaFromMR(mr);
	}

	public void preloadMedia(Media m) { // media base values not getting set. mediaCD =is= though. This is by design. See Phil's comments at bottom of this file
		if (m == null) return;
		MediaResult mr = new MediaResult();
		mr.media = m;

		this.preloadMediaToLocalFile(mr);
	}

	public void loadMediaFromMR(MediaResult mr) {
		boolean audioVideo = !(mr.media.type().equals("IMAGE"));
		Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id );
		if (audioVideo && mr.media.localURL() != null) {
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, "Audio/video with media ID: " + mr.media.mediaCD.media_id + " downloaded to local URL");
			this.mediaLoadedForMR(mr);
		}
		else if (mr.media.thumb != null) {
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Has Thumnail. All Done Loading");
			this.mediaLoadedForMR(mr);
		} // done. Media is fully loaded — presumably
		else if (mr.media.data != null) {
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Get Thumnail.");
			this.deriveThumbForMR(mr); }
		else if (mr.media.localURL() != null) { // get from the file if it already has been loaded
			// from suggestion: http://stackoverflow.com/a/29862162
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Get Binary from File. mr.media.localURL().getPath(): " + mr.media.localURL().getPath());
//			File f = new File(mr.media.localURL().getPath()); // loading local file.
//			mr.media.data = AppUtils.decodeImageFile(f, 1280, 720);//.decodeStream(mr.media.localURL.openConnection().getInputStream());
			mr.media.data = BitmapFactory.decodeFile(mr.media.localURL().getPath());//.decodeStream(mr.media.localURL.openConnection().getInputStream());
			if (mr.media.data != null) loadMediaFromMR(mr); // possible infinite loop ?
			else { // Should never happen, but media is essential to game, so if it does, well, no point in trying to play game. fix it.
				this.mediaLoadFailed(mr);
			}
		}
		else if (mr.media.remoteURL() != null) { // todo: call a pollServer type method to get media data, but one that can handle load failure and schedule to reload.
			// set up an async server request to get Media data
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Get Media Data from URL.");
			pollServerForMediaWithRemoteURL(mr);
		}
		else if (mr.media.remoteURL() == null) {
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Got Nothing Yet. Calling Load From MR.");
			this.loadMetaDataForMR(mr); } // populate remoteURL
	}

	private void mediaLoadFailed(MediaResult mr) { // you don't want to go here.
		Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, "FAILURE in ARISMediaLoader.. Unable to load binary data from local file. localURL = " + mr.media.localURL);
		Toast t = Toast.makeText(mGamePlayAct.getApplicationContext(), "There was a problem loading the media data for this game. Please report this to ARIS (Android) team.",
				Toast.LENGTH_SHORT);
		t.setGravity(Gravity.CENTER, 0, 0);
		t.show();
		mGamePlayAct.leaveGame();
	}


//	attmept to break preloadMedia into file upload only phase.
	public void preloadMediaToLocalFile(MediaResult mr) {
		if (mr.media.localURL() != null) { // don't count it until it's actually loaded form server
			this.mediaPreloadedForMR(mr);
			return;
		}
		mediaPreloadInProgress = true;
		if (mr.media.remoteURL() != null) { // todo: call a pollServer type method to get media data, but one that can handle load failure and schedule to reload.
			// set up an async server request to get Media data
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Get Media Data from URL.");
			preloadingToFile = true; // flag result that we're just preloading so it doesn't return to full media load aka: loadMediaFromMR()
			pollServerForMediaWithRemoteURL(mr);
			preloadingToFile = false;
		}
		else if (mr.media.remoteURL() == null) {
			Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Load Media from MR Id:" + mr.media.mediaCD.media_id + " Got Nothing Yet. Calling Load From MR.");
			this.loadMetaDataForMR(mr);
			// remoteURL should now have a legit value; attempt to download media to local file again.
			if (mr.media.remoteURL() != null) preloadMediaToLocalFile(mr);
		}

	}

	private void pollServerForMediaWithRemoteURL(final MediaResult mediaResult) {
		mGamePlayAct.showProgress(true);
		final Context context = mGamePlayAct;
		final String remoteURL = mediaResult.media.remoteURL().toString();
		String[] allowedContentTypes = new String[]{"image/png", "image/jpeg", "image/gif",
				"audio/mp4", "audio/mpeg", "video/mov", "video/mpeg", "video/mp4", "video/JPEG"};
		if (AppUtils.isNetworkAvailable(mGamePlayAct.getApplicationContext())) {
			// todo: loading status bar here.
			// showProgress(true);

//			if (mediaResult.connection != null) mediaResult.connection.cancelRequests(context, true); // todo: why?
			mediaResult.connection = new AsyncHttpClient(); // storing client ref in the MR so it can be tracked and cancelled if necessary.
//			mediaResult.connection.setTimeout(6000); //set timeout for 60 sec. (6000ms)
			mediaResult.connection.setMaxRetriesAndTimeout(2, 4000);
			Log.d(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, getClass().getSimpleName() + " SENDING request for mediaResult.media.mediaCD.media_id: " + mediaResult.media.mediaCD.media_id + " remoteURL: " + mediaResult.media.remoteURL());

			mediaResult.connection.get(context, remoteURL, new BinaryHttpResponseHandler(allowedContentTypes /*, looper here? */) { // the looper might be able to handle failed attempts?
				@Override
				public void onSuccess(int statusCode, Header[] headers, byte[] mediaBytes) {
					mGamePlayAct.showProgress(false);
					processLoadedBitmapForMR(mediaBytes, mediaResult);
				}
				@Override
				public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
					mGamePlayAct.showProgress(false);
					processFailedMRLoad(mediaResult);
					Log.d(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, getClass().getSimpleName() + " ## FAILED ## request for mediaResult.media.mediaCD.media_id: " + mediaResult.media.mediaCD.media_id + " remoteURL: " + mediaResult.media.remoteURL());
				}

//				@Override
//				public void onProgress(int remaining, int total) {
////					Log.d(AppConfig.LOGTAG, getClass().getSimpleName() + "AsyncHttpClient Progress for Req: " + requestApi + ". Progress: " + remaining + "/" + total);
//					// todo: set up progress bars of some sort for each request.
//				}
			});
		}
		else {
			// todo: handle network unavailable.
			mGamePlayAct.showProgress(false);

		}
	}

	// In iOS this would be more like  (void) connection:(NSURLConnection *)c didReceiveData:(NSData *)d
	//  and connectionDidFinishLoading()
	private void processLoadedBitmapForMR(byte[] mediaBytes, MediaResult mr) {
		Log.d(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, getClass().getSimpleName() + "RECEIVING request for mediaResult.media.mediaCD.media_id:" + mr.media.mediaCD.media_id);

		// save the returned data directly into the media.data field.
		mr.data = BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.length);
		mr.media.data = mr.data; // saving the data in two places or are these sharing a reference? Hopefully the latter.
		// save to local file
		ContextWrapper cw = new ContextWrapper(mGamePlayAct);
		// path to /data/data/appName/app_data/gameMedia_(game_id)
		File directory = cw.getDir("gameMedia_" + mGamePlayAct.mGame.game_id, Context.MODE_PRIVATE);
		// Create directory with file.
		File localMediaPath = new File(directory, mr.media.media_id + "." + mr.media.fileExtension());

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(localMediaPath);
			fos.write(mediaBytes);
			fos.close();
		} catch (FileNotFoundException fnfe) {
			fnfe.printStackTrace();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

		mr.media.setPartialLocalURL(localMediaPath.getAbsolutePath());
		// like saveAlteredMedia in iOS
		mGamePlayAct.mMediaModel.addOrUpdateMediaCD(mr.media.mediaCD); // todo: not sure if this is required or desired here. Remove if it's wrong.

		try {
			mr.media.localURL = new URL(localMediaPath.getAbsolutePath());
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		if (preloadingToFile) // attempt to break preload into file upload only phase
			this.preloadMediaToLocalFile(mr);
		else
			this.loadMediaFromMR(mr); // full media load.
//		this.loadMediaFromMR(mr);
	}

	private void processFailedMRLoad(MediaResult mediaToLoad) {
		// todo: handle download fail.
		Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " FAILURE in ARISMediaLoader..processFailedMRLoad() Unable to load binary data from local file. localURL = " + mediaToLoad.media.localURL);
	}

	public void loadMetaDataForMR(MediaResult mr) {
		// todo: this looks all wrong.
		for (int i = 0; i < metaConnections.size(); i++) {
//		for (MediaResult existingMR : metaConnections) { //not sure if this iteration style will allow proper referencing to original MR objects or spin off new one's that will dissolve after the loop.
			MediaResult existingMR = metaConnections.get(i);
			if (existingMR.media.media_id() == mr.media.media_id()) { // this makes the bold assumption that mediaid is not 0.
				// If mediaresult already exists, merge delegates to notify rather than 1.Throwing new request out (need to keep delegate) or 2.Redundantly requesting
//				existingMR.delegateHandles = existingMR.delegateHandles arrayByAddingObjectsFromArray:mr.delegateHandles;
//				existingMR.delegateHandles.addAll(mr.delegateHandles);
				return;
			}
		}
		metaConnections.add(mr);// addObject:mr;
		Log.e(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, " Calling Fetch Media by ID:" + mr.media.media_id );

		mGamePlayAct.mAppServices.fetchMediaById(mr.media.media_id()); //_SERVICES_ fetchMediaById:mr.media.media_id;
	}

	// calling stack as formed at start of game or continue game:
	//  this.retryLoadingAllMedia()
	//  <- listen from Dispatcher.model_media_available() <-
	//  MediaModel.updateMedias() <-- call to Dispatcher.model_media_available()
	//  MediaModel.mediasReceived()
	//  <- Disptcher.services_medias_received() <-
	//  ResponseHandler.processJsonHttpResponse( HTTP_GET_MEDIA_4_GAME ... ) <-- call to Dispatcher.services_medias_received()
	//  (From among others...) MediaModel.requestGameData <-- ergo, ...AppServices.fetchMedias()
	//  .........................Game.requestGameData()
	//  .........................GamePlayActivity.requestGameData()
	//  .........................GamePlayActivity.onStart() <-- loading sequence at start of game (or continue game)
	public void retryLoadingAllMedia() {
		mediaDataLoaded = 0;
		int initialLoadCount = mGamePlayAct.mMediaModel.mediaIDsToLoad.size();
		if (initialLoadCount > LOAD_MEDIA_AT_ONCE) initialLoadCount = LOAD_MEDIA_AT_ONCE;
		// walk through list of all media meta data MediaModel.mediaIdsToLoad array (ids of urls that need to gat their (binary) data from server)
		for (int i = 0; i < initialLoadCount; i++) {
			Integer mediaIdToLoad = mGamePlayAct.mMediaModel.mediaIDsToLoad.get(i);
			// dispatch an async service to try and load this data into a MediaResult obj
			Media mediaToLoad = mGamePlayAct.mMediaModel.mediaForId(mediaIdToLoad);
			this.pollServerWithMediaCD(mediaToLoad.mediaCD.remoteURL, mediaToLoad.mediaCD);
			// in that call: if call succeeds, add image to DB with it's data or just save as a file and put the rest in DB
			//  if load failed, leave it in the queue to be retried.
		}
	}

	public void mediaLoadedForMR(MediaResult mr) {
			if (mr.media != null) { // todo: rewrote this for delegate-less logic. Test. test. test.
				Log.d(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, getClass().getSimpleName() + " Calling mediaLoaded(): " + mr.media.mediaCD.media_id);
				mGamePlayAct.mMediaModel.mediaLoaded(mr.media);       // mediaLoaded(mr.media);
			}

		//deligateHandle way
//		for (int i = 0; i < mr.delegateHandles.size(); i++) {
//			ARISDelegateHandle dh = mr.delegateHandles.get(i);
//			if (dh.delegate() != null) { // todo see about a java equiv of this call: && dh.delegate class conformsToProtocol(protocol(ARISMediaLoaderDelegate))
//				MediaModel.class.cast(dh.delegate()).mediaLoaded(mr.media);       // mediaLoaded(mr.media);
//			}
//		}
	}

	public void mediaPreloadedForMR(MediaResult mr) {
		if (mr.media != null) { // todo: rewrote this for delegate-less logic. Test. test. test.
			Log.d(AppConfig.LOGTAG + AppConfig.LOGTAG_D2, getClass().getSimpleName() + " Calling mediaLoaded(): " + mr.media.mediaCD.media_id);
			mGamePlayAct.mMediaModel.mediaPreloaded(mr.media);       // mediaLoaded(mr.media);
		}
	}

	public void deriveThumbForMR(MediaResult mr) {
		Bitmap data = mr.media.data;
		int h = 128;
		int w = 128;

		String type = mr.media.type();
		if (type.contentEquals("IMAGE")) {
			mr.media.thumb = ThumbnailUtils.extractThumbnail(data, h, w);
//			i = UIImage imageWithData:data;
		}
		else if (type.contentEquals("VIDEO")) {
			mr.media.thumb = ThumbnailUtils.createVideoThumbnail(mr.media.localURL.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
			if (mr.media.thumb == null) {
				Resources res = mGamePlayAct.getResources();
				Drawable drawable = res.getDrawable(R.drawable.notebk_video_2x);
				mr.media.thumb = ((BitmapDrawable) drawable).getBitmap();
			}
//			AVAsset asset = AVAsset assetWithURL:mr.media.localURL;
//			AVAssetImageGenerator imageGenerator = AVAssetImageGenerator allocinitWithAsset:asset;
//			CMTime t = asset duration;
//			t.value = 1000;
//			CGImageRef imageRef = imageGenerator copyCGImageAtTime:t actualTime:NULL error:NULL;
//			i = UIImage imageWithCGImage:imageRef;
//			CGImageRelease(imageRef);  // CGImageRef won't be released by ARC
		}
		else if (type.contentEquals("AUDIO")) {
			Resources res = mGamePlayAct.getResources();
			Drawable drawable = res.getDrawable(R.drawable.default_audio_icon);
			mr.media.thumb = ((BitmapDrawable) drawable).getBitmap();
//			i = UIImage imageNamed("microphone"); //hack
		}
//		if (!i) i = UIImage imageNamed("logo_icon");
//
//		int s = 128;
//		int w = s;
//		int h = s;
//		if(i.size.width > i.size.height)
//			h = i.size.height  (s/i.size.width);
//		else
//			w = i.size.width  (s/i.size.height);
//		UIGraphicsBeginImageContext(CGSizeMake(w,h));
//		i drawInRect:CGRectMake(0,0,w,h);
//		UIImage newImage = UIGraphicsGetImageFromCurrentImageContext();
//		UIGraphicsEndImageContext();

//		mr.media.thumb = UIImagePNGRepresentation(newImage);
		this.loadMediaFromMR(mr);
	}

//	public void mediaResultThumbFound(NSNotification)notification
//	{
//		MediaResult mr = notification.userInfo@"media_result";
//		this.loadMediaFromMR(mr);
//	}

	/**
	 * retrieve media bitmap data from server
	 */
	public void pollServerWithMediaCD(final String requestURL, final MediaCD mediaCDToLoad) {
		mGamePlayAct.showProgress(true);

		RequestParams rqParams = new RequestParams();

		final Context context = mGamePlayAct;
//		final String request_url = AppConfig.SERVER_URL_MOBILE + requestURL;

//		StringEntity entity;
//		entity = null;
		String[] allowedContentTypes = new String[]{"image/png", "image/jpeg", "image/gif"};

		// Get request
		if (AppUtils.isNetworkAvailable(mGamePlayAct.getApplicationContext())) {

			AsyncHttpClient client = new AsyncHttpClient();
			client.setMaxRetriesAndTimeout(2, 10000);
			Log.d(AppConfig.LOGTAG, getClass().getSimpleName() + "AsyncHttpClient Sending Req for Media Data: " + requestURL);
			client.get(context, requestURL, new BinaryHttpResponseHandler(allowedContentTypes /*, looper here? */) { // the looper might be able to handle failed attempts?
				@Override
				public void onSuccess(int statusCode, Header[] headers, byte[] mediaBytes) {
					mGamePlayAct.showProgress(false);
					processLoadedMediaForCDToLocalFile(mediaBytes, mediaCDToLoad);
				}

				@Override
				public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
					mGamePlayAct.showProgress(false);
					processFailedMediaForCDLoad(mediaCDToLoad);
				}
//				@Override
//				public void onProgress(int remaining, int total) {
////					Log.d(AppConfig.LOGTAG, getClass().getSimpleName() + "AsyncHttpClient Progress for Req: " + requestApi + ". Progress: " + remaining + "/" + total);
//					// todo: set up progress bars of some sort for each request.
//				}
			});
		}
		else {
			// todo: what to do when internet is unavailable. A toast is not what we want here. May even want to test connectivity in calling method.
//			Toast t = Toast.makeText(mGamePlayAct.getApplicationContext(), "You are not connected to the internet currently. Please try again later.",
//					Toast.LENGTH_SHORT);
//			t.setGravity(Gravity.CENTER, 0, 0);
//			t.show();
			mGamePlayAct.showProgress(false);
		}

	}

	/*
	     Android replacement of connectionDidFinishLoading();
	     Save data to local file, and populate the localURL with file location
    */
	private String processLoadedMediaForCDToLocalFile(byte[] mediaBytes, MediaCD mediaCDToLoad) {
		// save binary to a local file.
		// enter this media data into database and a local URI to the file (Should there
		// already be a row for this from prior entry or upstream empty placeholder stub?)
		ContextWrapper cw = new ContextWrapper(mGamePlayAct);
		// path to /data/data/appName/app_data/gameMedia_(game_id)
		File directory = cw.getDir("gameMedia_" + mGamePlayAct.mGame.game_id, Context.MODE_PRIVATE);
		// Create directory with file.
		File mypath = new File(directory, mediaCDToLoad.media_id + "." + mediaCDToLoad.fileExtension());

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(mypath);

			fos.write(mediaBytes);
			fos.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		mediaCDToLoad.localURL = mypath.getAbsolutePath();

		// update DB to reflect new local URL
		mGamePlayAct.mMediaModel.addOrUpdateMediaCD(mediaCDToLoad);

		// try loading another media if needed
		mediaDataLoaded++;
		int loadMediaNext = mediaDataLoaded + LOAD_MEDIA_AT_ONCE - 1;
		if (loadMediaNext < mGamePlayAct.mMediaModel.mediaIDsToLoad.size()) {
			Integer mediaIdToLoad = mGamePlayAct.mMediaModel.mediaIDsToLoad.get(loadMediaNext);
			Media mediaToLoad = mGamePlayAct.mMediaModel.mediaForId(mediaIdToLoad);
			this.pollServerWithMediaCD(mediaToLoad.mediaCD.remoteURL, mediaToLoad.mediaCD);
		}

		return mypath.getAbsolutePath();
	}

	private void processFailedMediaForCDLoad(MediaCD mediaToLoad) {
		// todo: finish me
	}
}

/*
From Phil regarding the local storage of media and mediaCD.

Ah. So, long story short, mediaCD is necessary to be able to use Core Data. Core Data is some
abstraction on SQL Lite that Apple offers for maintaining a local database. The idea is that it's
smart and can sync things for you and keep complex relationships without you worrying how that maps
to a relational database (it "does all the work for you").

The frustrating part for me is that, especially with simple objects, "storing them to and from a
database" isn't a problem sufficiently complex as to require such a complicated layer of
indirection. I would have much rather just done the darn serialization/deserialization myself
(which I in fact did for the "offline" layer).

Anyways. This was developed when we were first playing with the idea of local caching. In this case,
media was the only thing we considered worthwhile to locally cache, and everyone was telling us to
use Core Data for it. So we did. Now, Core Data has a ton of caveats that you're "just supposed to
know" in order to use it effectively. One such caveat is "don't store binary data". Another is
"don't store primitives". Another is "URLs need to be translated to this or that or blah". Another
is "you're not allowed to create a media object unless it is related to some context and is to be
synced" which meant that for any transient uses of the media object, we needed to do this complex
handshake where we create a context, gen a media, do what we want with it, throw the context away,
etc... The point is, it's all really oddly complex and was throwing unique restrictions on just one
part of our codebase.

So, the way I chose to handle it (which may or may not have been the best decision) was to add yet
another layer of indirection. There was a media object where I could consider everything about is as
naturally as any other object in the codebase. And it had a MediaCD object reference for the times
it needed to interface with CoreData. And that's it.

One other bit of info that might not be straightforward is this: we cache the MediaCD (which is
essentially the metadata about a piece of media [its title, id, type, etc... not the binary content
itself]) within coredata. But when we actually download the binary media, we just store that in a
file in the local filesystem (because coredata is terribly unperformant loading/syncing/saving large
swathes of binary data), and store something like "LocalURL" within the MediaCD.

*/