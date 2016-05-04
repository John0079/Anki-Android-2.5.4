/****************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General private License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General private License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General private License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki;

import android.content.ContentValues;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Pair;

import com.ichi2.anki.AnkiDatabaseManager;
import com.ichi2.anki.AnkiDb;
import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.anki.R;
import com.ichi2.anki.UIUtils;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.libanki.hooks.Hooks;
import com.ichi2.libanki.template.Template;
import com.ichi2.utils.VersionUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import timber.log.Timber;

// Anki maintains a cache of used tags so it can quickly present a list of tags
// for autocomplete and in the browser. For efficiency, deletions are not
// tracked, so unused tags can only be removed from the list with a DB check.
//
// This module manages the tag cache and tags for notes.

public class Collection {

    private AnkiDb mDb;
    private boolean mServer;
    // 上一次保存时间
    private double mLastSave;
    private Media mMedia;
    private Decks mDecks;
    private Models mModels;
    private Tags mTags;

    private Sched mSched;

    private double mStartTime;
    private int mStartReps;

    // BEGIN: SQL table columns
    private long mCrt;
    private long mMod;
    private long mScm;
    // dirty whether full sync was require?
    private boolean mDty;
    private int mUsn;
    // Todo_john ls last sync 最后一次同步
    private long mLs;
    private JSONObject mConf;
    // END: SQL table columns

    private LinkedList<Object[]> mUndo;

    private String mPath;
    private boolean mDebugLog;
    private PrintWriter mLogHnd;

    private static final Pattern fClozePatternQ = Pattern.compile("\\{\\{(?!type:)(.*?)cloze:");
    private static final Pattern fClozePatternA = Pattern.compile("\\{\\{(.*?)cloze:");
    private static final Pattern fClozeTagStart = Pattern.compile("<%cloze:");

    // other options 配置信息，它对应着col表中的conf字段；
    public static final String defaultConf = "{"
            +
            // review options
            "'activeDecks': [1], " + "'curDeck': 1, " + "'newSpread': " + Consts.NEW_CARDS_DISTRIBUTE + ", "
            + "'collapseTime': 1200, " + "'timeLim': 0, " + "'estTimes': True, " + "'dueCounts': True, "
            +
            // other config
            "'curModel': null, " + "'nextPos': 1, " + "'sortType': \"noteFld\", "
            + "'sortBackwards': False, 'addToCur': True }"; // add new to currently selected deck?

    public static final int UNDO_REVIEW = 0;
    public static final int UNDO_BURY_NOTE = 2;
    public static final int UNDO_SUSPEND_CARD = 3;
    public static final int UNDO_SUSPEND_NOTE = 4;
    public static final int UNDO_DELETE_NOTE = 5;
    public static final int UNDO_BURY_CARD = 7;

    private static final int[] fUndoNames = new int[]{
        R.string.undo_action_review,
        R.string.undo_action_edit,
        R.string.undo_action_bury,
        R.string.undo_action_suspend_card,
        R.string.undo_action_suspend_note,
        R.string.undo_action_delete,
        R.string.undo_action_mark};

    private static final int UNDO_SIZE_MAX = 20;

    public Collection(AnkiDb db, String path) {
        this(db, path, false);
    }

    public Collection(AnkiDb db, String path, boolean server) {
        this(db, path, false, false);
    }

    public Collection(AnkiDb db, String path, boolean server, boolean log) {
        mDebugLog = log;
        mDb = db;
        mPath = path;
        _openLog();
        log(path, VersionUtils.getPkgVersionName());
        mServer = server;
        mLastSave = Utils.now();
        clearUndo();
        mMedia = new Media(this, server);
        mModels = new Models(this);
        mDecks = new Decks(this);
        mTags = new Tags(this);
        load();
        if (mCrt == 0) {
            mCrt = UIUtils.getDayStart() / 1000;
        }
        mStartReps = 0;
        mStartTime = 0;
        mSched = new Sched(this);
        if (!mConf.optBoolean("newBury", false)) {
            boolean mod = mDb.getMod();
            mSched.unburyCards();
            mDb.setMod(mod);
        }
    }


    public String name() {
        String n = (new File(mPath)).getName().replace(".anki2", "");
        // TODO:
        return n;
    }


    /**
     * DB-related *************************************************************** ********************************
     */

    public void load() {
        Cursor cursor = null;
        try {
            // Read in deck table columns
            cursor = mDb.getDatabase().rawQuery(
                    "SELECT crt, mod, scm, dty, usn, ls, " +
                    "conf, models, decks, dconf, tags FROM col", null);
            if (!cursor.moveToFirst()) {
                return;
            }
            mCrt = cursor.getLong(0);
            mMod = cursor.getLong(1);
            mScm = cursor.getLong(2);
            mDty = cursor.getInt(3) == 1; // No longer used
            mUsn = cursor.getInt(4);
            mLs = cursor.getLong(5);
            try {
                mConf = new JSONObject(cursor.getString(6));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            mModels.load(cursor.getString(7));
            mDecks.load(cursor.getString(8), cursor.getString(9));
            mTags.load(cursor.getString(10));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }


    /**
     * Mark DB modified. DB operations and the deck/tag/model managers do this automatically, so this is only necessary
     * if you modify properties of this object or the conf dict.
     */
    public void setMod() {
        mDb.setMod(true);
    }


    public void flush() {
        flush(0);
    }


    /**
     * Flush state to DB, updating mod time.
     */
    public void flush(long mod) {
        Timber.i("flush - Saving information to DB...");
        mMod = (mod == 0 ? Utils.intNow(1000) : mod);
        ContentValues values = new ContentValues();
        values.put("crt", mCrt);
        values.put("mod", mMod);
        values.put("scm", mScm);
        values.put("dty", mDty ? 1 : 0);
        values.put("usn", mUsn);
        values.put("ls", mLs);
        values.put("conf", Utils.jsonToString(mConf));
        mDb.update("col", values);
    }


    /**
     * Flush, commit DB, and take out another write lock.
     */
    public synchronized void save() {
        save(null, 0);
    }


    public synchronized void save(long mod) {
        save(null, mod);
    }

    /**
     * 写入数据库，这个方法要加上同步锁
     * @param name
     * @param mod
     */
    public synchronized void save(String name, long mod) {
        // let the managers conditionally flush
        mModels.flush();
        mDecks.flush();
        mTags.flush();
        // and flush deck + bump mod if db has been changed
        if (mDb.getMod()) {
            // 如果mDb修改过
            flush(mod);
            mDb.commit();
            lock();
            // 设置修改；
            mDb.setMod(false);
        }
        // undoing non review operation is handled differently in ankidroid
//        _markOp(name);
        mLastSave = Utils.now();
    }


    /** Save if 5 minutes has passed since last save.
     * 每隔五分钟自动保存一次
     * */
    public void autosave() {
        if ((Utils.now() - mLastSave) > 300) {
            save();
        }
    }


    /** make sure we don't accidentally bump mod time
     * 锁上，确保我们不能偶然触及磕碰到修改时间；
     * */
    public void lock() {
        // make sure we don't accidentally bump mod time
        boolean mod = mDb.getMod(); //数据库现在正在修改吗？
        //Todo_john 这句话什么意思啊！UPDATE col SET mod=mod
        mDb.execute("UPDATE col SET mod=mod");
        // 设置col表中的
        mDb.setMod(mod);
    }


    /**
     * Disconnect from DB.
     */
    public synchronized void close() {
        close(true);
    }


    /**
     * 断开与数据库的链接
     * @param save
     */
    public synchronized void close(boolean save) {
        if (mDb != null) {
            // 取出“newBury”的值，如果没有这个字段，则返回false
            if (!mConf.optBoolean("newBury", false)) {
                boolean mod = mDb.getMod();
                // 取消被搁置的卡片；
                mSched.unburyCards();
                // Todo_john mod为什么取出来有设置上呢？啥意思？
                mDb.setMod(mod);
            }
            try {
                SQLiteDatabase db = getDb().getDatabase();
                if (save) {
                    db.beginTransaction();
                    try {
                        save();
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                } else {
                    if (db.inTransaction()) {
                        db.endTransaction();
                    }
                    lock();
                }
            } catch (RuntimeException e) {
                AnkiDroidApp.sendExceptionReport(e, "closeDB");
            }
            AnkiDatabaseManager.closeDatabase(mPath);
            mDb = null;
            mMedia.close();
            _closeLog();
            Timber.i("Collection closed");
        }
    }

    // 重新打开数据库
    public void reopen() {
        if (mDb == null) {
            mDb = AnkiDatabaseManager.getDatabase(mPath);
            mMedia.connect();
            _openLog();
        }
    }


    /** Note: not in libanki.
     * 标记一个修改过的schema，用于强制同步；
     * Mark schema modified to force a full sync, but with the confirmation checking function disabled
     * This is a convenience method which doesn't throw ConfirmModSchemaException
     */
    public void modSchemaNoCheck() {
        try {
            modSchema(false);
        } catch (ConfirmModSchemaException e) {
            // This will never be reached as we disable confirmation via the "false" argument
            throw new RuntimeException(e);
        }
    }

    /** Mark schema modified to force a full sync.
     * 标记一个修改过的schema，用于强制同步；
     * ConfirmModSchemaException will be thrown if the user needs to be prompted to confirm the action.
     * If the user chooses to confirm then modSchema(false) should be called, after which the exception can
     * be safely ignored, and the outer code called again.
     *
     * @throws ConfirmModSchemaException */
    public void modSchema() throws ConfirmModSchemaException {
        modSchema(true);
    }

    /** Mark schema modified to force a full sync.
     * 标记一个修改过的schema，用于强制同步；
     * If check==true and the schema has not already been marked modified then ConfirmModSchemaException will be thrown.
     * If the user chooses to confirm then modSchema(false) should be called, after which the exception can
     * be safely ignored, and the outer code called again.
     *
     * @param check
     * @throws ConfirmModSchemaException
     */
    public void modSchema(boolean check) throws ConfirmModSchemaException {
        if (!schemaChanged()) {
            if (check) {
                /* In Android we can't show a dialog which blocks the main UI thread
                 Therefore we can't wait for the user to confirm if they want to do
                 a full sync here, and we instead throw an exception asking the outer
                 code to handle the user's choice */
                throw new ConfirmModSchemaException();
            }
        }
        mScm = Utils.intNow(1000);
        setMod();
    }


    /** True if schema changed since last sync.
     * 返回schema是否改变过，
     * */
    public boolean schemaChanged() {
        return mScm > mLs;
    }


    public int usn() {
        if (mServer) {
            return mUsn;
        } else {
            return -1;
        }
    }


    /** called before a full upload
     * 全部上传之前要做事情；
     * */
    public void beforeUpload() {
        String[] tables = new String[] { "notes", "cards", "revlog" };
        // 首先把这三个表的统一序列号修改成1;
        for (String t : tables) {
            mDb.execute("UPDATE " + t + " SET usn=0 WHERE usn=-1");
        }
        // we can save space by removing the log of deletions,为了节省空间，我们可以移除那些删除的log记录
        // Todo_john 这个graves就是用来记录删除的东西的吗？
        mDb.execute("delete from graves");
        mUsn += 1;  //统一序列化号递增加一；
        mModels.beforeUpload();   // 把所有笔记类型的usn设置为0；
        mTags.beforeUpload();   // 把tags的所有key都设置为0；
        mDecks.beforeUpload();  // 把所有deck的usn都设置为0，把所有deck对应的conf中的usn也都设置为0
        modSchemaNoCheck();    // 修改 mScm = Utils.intNow(1000); 修改setMod(true);
        mLs = mScm;
        // ensure db is compacted before upload 确保数据库是压实的，
        mDb.execute("vacuum"); //  VACUUM命令复制主数据库文件到临时数据库并从临时数据库重新载入主数据库
        mDb.execute("analyze"); // ANALYZE命令令集合关于索引的统计信息并将它们储存在数据库的一个特殊表中，查询优化器可以用该表来制作更好的索引选择。若不给出参数，所有附加数据库中的所有索引被分析
        close();
    }


    /**
     * Object creation helpers **************************************************
     * *********************************************
     */

    // 给个卡片的id，获取所对应的卡片
    public Card getCard(long id) {
        return new Card(this, id);
    }

    // 给出笔记的id,获取所对应的笔记
    public Note getNote(long id) {
        return new Note(this, id);
    }


    /**
     * Utils ******************************************************************** ***************************
     * 获取conf里面nextXxx的属性值，比如nextPos的属性值，然后对此属性值累加1；
     * 在这里用到这个方法： data.add(new Object[] { ts, nid, did, tord, now, usn, nextID("pos") });
     * 就此代码，分析；
     */

    public int nextID(String type) {
        type = "next" + Character.toUpperCase(type.charAt(0)) + type.substring(1);
        // type --> "nextPos"
        int id;
        try {
            // 取出col表中conf字段的nextPos属性值；
            id = mConf.getInt(type);
        } catch (JSONException e) {
            id = 1;
        }
        try {
            // 取出col表中conf字段的nextPos属性值；并对此属性值累加1；并返回这个值；
            mConf.put(type, id + 1);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return id;
    }


    /**
     * Rebuild the queue and reload data after DB modified.
     * 数据库修改后从新建立队列和加载数据；
     */
    public void reset() {
        mSched.reset();
    }


    /**
     * Deletion logging ********************************************************* **************************************
     * 登记记录删除的卡片，
     */

    public void _logRem(long[] ids, int type) {
        for (long id : ids) {
            ContentValues values = new ContentValues();
            values.put("usn", usn());
            values.put("oid", id);
            values.put("type", type);
            mDb.insert("graves", null, values);
        }
    }


    /**
     * Notes ******************************************************************** ***************************
     */
    // 返回所有的笔记条数总和；
    public int noteCount() {
        return (int) mDb.queryScalar("SELECT count() FROM notes");
    }

    /**
     * Return a new note with the default model from the deck
     * 返回以个新的笔记，按照默认的笔记类型；
     * @return The new note
     */
    public Note newNote() {
        return newNote(true);
    }

    /**使用当前的笔记模型创建一条新的笔记返回；
     * Return a new note with the model derived from the deck or the configuration
     * @param forDeck When true it uses the model specified in the deck (mid), otherwise it uses the model specified in
     *                the configuration (curModel)
     * @return The new note
     */
    public Note newNote(boolean forDeck) {
        return newNote(mModels.current(forDeck));
    }

    /**
     * Return a new note with a specific model 使用指定的笔记类型创造出一个新的笔记返回，
     * @param m The model to use for the new note
     * @return The new note
     */
    public Note newNote(JSONObject m) {
        return new Note(this, m);
    }


    /**
     * Add a note to the collection. Return number of new cards.
     * 添加一个笔记到牌组集合，返回由此笔记产生的卡片的数量
     */
    public int addNote(Note note) {
        // check we have card models available, then save
        ArrayList<JSONObject> cms = findTemplates(note);
        if (cms.size() == 0) {
            return 0;
        }
        note.flush();
        // deck conf governs which of these are used
        // Todo_john 获取nextPos的值是1， 获取它有什么意义呢????
        int due = nextID("pos");
        // add cards
        int ncards = 0;
        for (JSONObject template : cms) {
            _newCard(note, template, due);
            ncards += 1;
        }
        return ncards;
    }


    public void remNotes(long[] ids) {
        ArrayList<Long> list = mDb
                .queryColumn(Long.class, "SELECT id FROM cards WHERE nid IN " + Utils.ids2str(ids), 0);
        long[] cids = new long[list.size()];
        int i = 0;
        for (long l : list) {
            cids[i++] = l;
        }
        remCards(cids);
    }


    /**
     * Bulk delete facts by ID. Don't call this directly.
     */
    public void _remNotes(long[] ids) {
        if (ids.length == 0) {
            return;
        }
        String strids = Utils.ids2str(ids);
        // we need to log these independently of cards, as one side may have
        // more card templates
        _logRem(ids, Consts.REM_NOTE);
        mDb.execute("DELETE FROM notes WHERE id IN " + strids);
    }


    /**
     * Card creation ************************************************************ ***********************************
     */

    /**
     * @return (active), non-empty templates.
     */
    private ArrayList<JSONObject> findTemplates(Note note) {
        JSONObject model = note.model();
        ArrayList<Integer> avail = mModels.availOrds(model, Utils.joinFields(note.getFields()));
        return _tmplsFromOrds(model, avail);
    }


    private ArrayList<JSONObject> _tmplsFromOrds(JSONObject model, ArrayList<Integer> avail) {
        ArrayList<JSONObject> ok = new ArrayList<JSONObject>();
        JSONArray tmpls;
        try {
            if (model.getInt("type") == Consts.MODEL_STD) {
                tmpls = model.getJSONArray("tmpls");
                for (int i = 0; i < tmpls.length(); i++) {
                    JSONObject t = tmpls.getJSONObject(i);
                    if (avail.contains(t.getInt("ord"))) {
                        ok.add(t);
                    }
                }
            } else {
                // cloze - generate temporary templates from first
                for (int ord : avail) {
                    JSONObject t = new JSONObject(model.getJSONArray("tmpls").getString(0));
                    t.put("ord", ord);
                    ok.add(t);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return ok;
    }


    /**
     * Generate cards for non-empty templates, return ids to remove.
     * 对传入的笔记集合，重新生成他们的卡片，返回需要删除的卡片的id集合；
     */
	public ArrayList<Long> genCards(List<Long> nids) {
	    return genCards(Utils.arrayList2array(nids));
	}
    // 将传入的笔记id数组，逐个产生所对应的卡片
    public ArrayList<Long> genCards(long[] nids) {
        // build map of (nid,ord) so we don't create dupes，创建一个字典，放置笔记id,和卡片模板序号，避免重复生成；
        // 将笔记的id连成一个字符串；snids
        String snids = Utils.ids2str(nids);
        // have中将保存<nid <ord cid>>
        HashMap<Long, HashMap<Integer, Long>> have = new HashMap<Long, HashMap<Integer, Long>>();
        // dids将保存<nid, did>
        HashMap<Long, Long> dids = new HashMap<Long, Long>();
        Cursor cur = null;
        try {
            // 从卡片表中取出形参给的笔记的Id的所有对应卡片，并取出卡片id，笔记id，牌组id，卡片模板序列号这些字段；
            cur = mDb.getDatabase().rawQuery("SELECT id, nid, ord, did FROM cards WHERE nid IN " + snids, null);
            while (cur.moveToNext()) {
                // existing cards
                long nid = cur.getLong(1);
                if (!have.containsKey(nid)) {
                    have.put(nid, new HashMap<Integer, Long>());
                }
                // have中将保存<nid <ord cid>>
                have.get(nid).put(cur.getInt(2), cur.getLong(0));
                // and their dids
                long did = cur.getLong(3);
                if (dids.containsKey(nid)) {
                    if (dids.get(nid) != 0 && dids.get(nid) != did) {
                        // cards are in two or more different decks; revert to model default
                        // 一个笔记生成的多张卡片分布在多个不同的牌组中，这时恢复默认模型；即，将当前笔记对应的
                        // 牌组的id，设置为0；
                        dids.put(nid, 0l);
                    }
                } else {
                    // first card or multiple cards in same deck
                    // 当dids中不包含nid这样的key ,就把<nid, did>放入到dids中；
                    dids.put(nid, did);
                }
            }
            // 结果得到一个did的map集合，这个集合中，nid与did一一对应，每个note,属于一个deck;
            // 也就是说一个笔记不能被多个deck所有，一个deck也不能有重复的note
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        // build cards for each note
        ArrayList<Object[]> data = new ArrayList<Object[]>();
        // 获得一个安全的，不会被重复的id，ts
        long ts = Utils.maxID(mDb);
        long now = Utils.intNow();
        ArrayList<Long> rem = new ArrayList<Long>();
        // usn 唯一序列号，unique serial number;
        int usn = usn();
        cur = null;
        try {
            cur = mDb.getDatabase().rawQuery("SELECT id, mid, flds FROM notes WHERE id IN " + snids, null);
            while (cur.moveToNext()) {
                JSONObject model = mModels.get(cur.getLong(1));
                /** 给一个笔记类型，和字段值，字段值被连接成字符串，返回一个可用的卡片模型索引号集合； */
                ArrayList<Integer> avail = mModels.availOrds(model, cur.getString(2));
                long nid = cur.getLong(0);
                // 找到当前笔记所在的牌组的id，即Deck的id
                long did = dids.get(nid);
                if (did == 0) {
                    //如果没有找到当前笔记所在的牌组，则把笔记放入到笔记所在的笔记类型的did属性所指示的牌组中，
                    did = model.getLong("did");
                }
                // add any missing cards 添加错过的卡片；
                for (JSONObject t : _tmplsFromOrds(model, avail)) {
                    int tord = t.getInt("ord");
                    // have中将保存<nid <ord cid>>
                    // dohave反应了，nid的第tord个卡片模板对应的卡片是否被生成；
                    boolean doHave = have.containsKey(nid) && have.get(nid).containsKey(tord);
                    if (!doHave) {
                        // 如果nid的第tord个卡片模板对应的卡片没有生成；则。。。
                        long ndid;
                        try {
                            // ndid对应着卡片模板着的那个did的属性值；
                            // ndid也可理解为此卡片模板对应的那个牌组的id；
                            ndid = t.getLong("did");
                            if (ndid != 0) {
                                // 如果卡片模板包含有牌组id，则此笔记所在的牌组id也变成这个牌组id;
                                did = ndid;
                            }
                        } catch (JSONException e) {
                            // do nothing
                        }
                        if (getDecks().isDyn(did)) {
                            // 判断当前笔记的牌组，是不是动态牌组，如果是，则这条笔记所在的牌组的id设置为1；
                            did = 1;
                        }
                        // if the deck doesn't exist, use default instead
                        // Todo_john 为什么要写这么一句，不是多此一举吗？did = mDecks.get(did).getLong("id");
                        // 或者是用来判断这个did是合法的did？
                        did = mDecks.get(did).getLong("id");
                        /**
                         * we'd like to use the same due# as sibling cards, but we can't retrieve that quickly, so we
                         * give it a new id instead
                         * 我们不想让这个卡片和它的兄弟卡片有同样的过期时间，我们又不能很快地回复，于是我们给它一个新的id去代替；
                         * 这里的data  ArrayList<Object[]> data = new ArrayList<Object[]>(); 它是一个集合，它保存着当前笔记生成的所有新卡片的信息；
                         * nextID("pos")这个值是 取出col表中conf字段的nextPos属性值累加1的结果；
                         */
                        data.add(new Object[] { ts, nid, did, tord, now, usn, nextID("pos") });
                        ts += 1;
                    }
                }
                // note any cards that need removing
                // have中将保存<nid <ord cid>>
                if (have.containsKey(nid)) {
                    for (Map.Entry<Integer, Long> n : have.get(nid).entrySet()) {
                        if (!avail.contains(n.getKey())) {
                            // 如果可用的卡片模板中，不包含这个卡片模板的索引号，则，此卡片模板对应的那个卡片应该被删除；
                            // 则放入rem集合中，rem即，remove;
                            rem.add(n.getValue());
                        }
                    }
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        // bulk update
        mDb.executeMany("INSERT INTO cards VALUES (?,?,?,?,?,?,0,0,?,0,0,0,0,0,0,0,0,\"\")", data);
        return rem;
    }


	/**
	 * Return cards of a note, without saving them
     * 返回一个笔记的卡片，但是不保存他们，只是用来预览；
	 * @param note The note whose cards are going to be previewed
     * @param type 0 - when previewing in add dialog, only non-empty 来自添加对话框的预览
     *             1 - when previewing edit, only existing 来自编辑状态的预览
     *             2 - when previewing in models dialog, all templates 来自笔记类型对话框的预览；
     * @return list of cards
	 */
	public List<Card> previewCards(Note note, int type) {
	    ArrayList<JSONObject> cms = null;
	    if (type == 0) {
	        cms = findTemplates(note);
	    } else if (type == 1) {
	        cms = new ArrayList<JSONObject>();
	        for (Card c : note.cards()) {
	            cms.add(c.template());
	        }
	    } else {
	        cms = new ArrayList<JSONObject>();
	        try {
                JSONArray ja = note.model().getJSONArray("tmpls");
                for (int i = 0; i < ja.length(); ++i) {
                    cms.add(ja.getJSONObject(i));
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
	    }
	    if (cms.isEmpty()) {
	        return new ArrayList<Card>();
	    }
	    List<Card> cards = new ArrayList<Card>();
	    for (JSONObject template : cms) {
	        cards.add(_newCard(note, template, 1, false));
	    }
	    return cards;
	}
    public List<Card> previewCards(Note note) {
        return previewCards(note, 0);
    }


    /**
     * 创建一个新的卡片返回，
     * @param note
     * @param template
     * @param due 它来自于conf里的nextPos属性的值；
     * @return
     */
    private Card _newCard(Note note, JSONObject template, int due) {
        return _newCard(note, template, due, true);
    }

    /**
     * 创建一个新的卡片返回，
     * @param note
     * @param template
     * @param due 它来自于conf里的nextPos属性的值；
     * @return
     */
    private Card _newCard(Note note, JSONObject template, int due, boolean flush) {
        Card card = new Card(this);
        card.setNid(note.getId());
        try {
            card.setOrd(template.getInt("ord"));
        } catch (JSONException e) {
            new RuntimeException(e);
        }
        long did;
        try {
            did = template.getLong("did");
        } catch (JSONException e) {
            did = 0;
        }
        try {
            card.setDid(did != 0 ? did : note.model().getLong("did"));
            // if invalid did, use default instead
            JSONObject deck = mDecks.get(card.getDid());
            if (deck.getInt("dyn") == 1) {
            	// must not be a filtered deck
            	card.setDid(1);
            } else {
                card.setDid(deck.getLong("id"));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        card.setDue(_dueForDid(card.getDid(), due));
        if (flush) {
            card.flush();
        }
        return card;
    }

    // 为这个牌组的新卡设置due的时间；这里的due来自于col中的conf字段的nextPos属性值，一般是1；
    // 返回一个1到1000的随机数；
    public int _dueForDid(long did, int due) {
        // 每个牌组都有一个他学习的配置文件，但是通常我们都没有配置此文件，因此所有牌组都通用一个学习的配置文件；
        JSONObject conf = mDecks.confForDid(did);
        // in order due?
        try {
            if (conf.getJSONObject("new").getInt("order") == Consts.NEW_CARDS_DUE) {
                // 如果是按照创建卡片的顺序则，直接返回due即可；
                return due;
            } else {
                // random mode; seed with note ts so all cards of this note get
                // the same random number
                Random r = new Random();
                // setSeed设置种子，设置完种子后，返回的随机数就是0到种子due之间的数字；
                r.setSeed(due);
                return r.nextInt(Math.max(due, 1000) - 1) + 1;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Cards ******************************************************************** ***************************
     */

    public boolean isEmpty() {
        // SELECT 1 FROM cards用来查询表中是否有记录；
        return mDb.queryScalar("SELECT 1 FROM cards LIMIT 1") == 0;
    }

    // 返回cards表中卡片的总数量；
    public int cardCount() {
        return mDb.queryScalar("SELECT count() FROM cards");
    }


    // NOT IN LIBANKI //
    public int cardCount(Long[] ls) {
        return mDb.queryScalar("SELECT count() FROM cards WHERE did IN " + Utils.ids2str(ls));
    }


    /**
     * Bulk delete cards by ID.
     * 批量删除卡片
     */
    public void remCards(long[] ids) {
    	remCards(ids, true);
    }
    // 批量删除卡片
    public void remCards(long[] ids, boolean notes) {
        if (ids.length == 0) {
            return;
        }
        String sids = Utils.ids2str(ids);
        long[] nids = Utils
                .arrayList2array(mDb.queryColumn(Long.class, "SELECT nid FROM cards WHERE id IN " + sids, 0));
        // remove cards
        _logRem(ids, Consts.REM_CARD);
        mDb.execute("DELETE FROM cards WHERE id IN " + sids);
        // then notes 是否将没有卡片的笔记也一同删除了，
        if (!notes) {
        	return;
        }
        // 找出那些没有卡片的笔记，一并删除；
        nids = Utils
                .arrayList2array(mDb.queryColumn(Long.class, "SELECT id FROM notes WHERE id IN " + Utils.ids2str(nids)
                        + " AND id NOT IN (SELECT nid FROM cards)", 0));
        _remNotes(nids);
    }

    // 倾入，倒入，完全加载卡片；
    public List<Long> emptyCids() {
        List<Long> rem = new ArrayList<>();
        for (JSONObject m : getModels().all()) {
            // nids(m)返回所有用到此笔记类型的笔记；
            // 所有的笔记都依次生成卡片并加入rem集合中，返回；
            rem.addAll(genCards(getModels().nids(m)));
        }
        return rem;
    }

    // 加载所有卡片的报告；
    // Todo_john 这个方法不很理解，用途是什么呢？
    public String emptyCardReport(List<Long> cids) {
        StringBuilder rep = new StringBuilder();
        Cursor cur = null;
        try {
            cur = mDb.getDatabase().rawQuery("select group_concat(ord+1), count(), flds from cards c, notes n "
                                           + "where c.nid = n.id and c.id in " + Utils.ids2str(cids) + " group by nid", null);
            while (cur.moveToNext()) {
                String ords = cur.getString(0);
                int cnt = cur.getInt(1);
                String flds = cur.getString(2);
                rep.append(String.format("Empty card numbers: %s\nFields: %s\n\n", ords, flds.replace("\u001F", " / ")));
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        return rep.toString();
    }

    /**
     * Field checksums and sorting fields ***************************************
     * 有给定的nids，去notes表中查询出相关note的，id，mid,以及flds信息，并写入一个集合返回；
     */

    private ArrayList<Object[]> _fieldData(String snids) {
        ArrayList<Object[]> result = new ArrayList<Object[]>();
        Cursor cur = null;
        try {
            cur = mDb.getDatabase().rawQuery("SELECT id, mid, flds FROM notes WHERE id IN " + snids, null);
            while (cur.moveToNext()) {
                result.add(new Object[]{cur.getLong(0), cur.getLong(1), cur.getString(2)});
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        return result;
    }


    /** Update field checksums and sort cache, after find&replace, etc.
     * 更新数据库笔记表中的内容，特别是更新排序字段，和csum字段，此字段由flds第一个值得哈希值而来，可以避免笔记重复；
     * */
    public void updateFieldCache(long[] nids) {
        String snids = Utils.ids2str(nids);
        ArrayList<Object[]> r = new ArrayList<Object[]>();
        // _fieldData 方法从给定的nids，去notes表中查询出相关note的，id，mid,以及flds信息，并写入一个集合返回；
        for (Object[] o : _fieldData(snids)) {
            String[] fields = Utils.splitFields((String) o[2]);
            JSONObject model = mModels.get((Long) o[1]);
            if (model == null) {
                // note point to invalid model
                continue;
            }
            // Utils.stripHTML()的作用是： 剥掉html标签，只留下输入字符串纯文本信息
            // Utils.fieldChecksum(fields[0])这个方法作用是，用于字段检验，它将一个字符串变成哈希值，但只取前8位，o[0]是笔记的id,
            // o[0]是当前笔记的id,
            r.add(new Object[] { Utils.stripHTML(fields[mModels.sortIdx(model)]), Utils.fieldChecksum(fields[0]), o[0] });
        }
        // apply, relying on calling code to bump usn+mod
        // 更新数据库notes表中的sfld字段和csum字段，csum字段用来避免note的重复；
        mDb.executeMany("UPDATE notes SET sfld=?, csum=? WHERE id=?", r);
    }


    /**
     * Q/A generation *********************************************************** ************************************
     */

    public ArrayList<HashMap<String, String>> renderQA() {
        return renderQA(null, "card");
    }

    // 渲染问题和答案；
    public ArrayList<HashMap<String, String>> renderQA(int[] ids, String type) {
        String where;
        if (type.equals("card")) {
            where = "AND c.id IN " + Utils.ids2str(ids);
        } else if (type.equals("fact")) {
            where = "AND f.id IN " + Utils.ids2str(ids);
        } else if (type.equals("model")) {
            where = "AND m.id IN " + Utils.ids2str(ids);
        } else if (type.equals("all")) {
            where = "";
        } else {
            throw new RuntimeException();
        }
        ArrayList<HashMap<String, String>> result = new ArrayList<HashMap<String, String>>();
        for (Object[] row : _qaData(where)) {
            result.add(_renderQA(row));
        }
        return result;
    }


    /**
     * Returns hash of id, question, answer.
     * 返回
     */
    public HashMap<String, String> _renderQA(Object[] data) {
        return _renderQA(data, null, null);
    }

    /**
     * 返回// 最终d中保存三个元素，它是个字典：具体内容：
     * "q"->"今天星期几啊"
     * "a"->"今天星期几呀\n\n<hr id=answer>\n\n星期三吧"
     * "id"->"41276839263"
     * @param data is [cid, nid, mid, did, ord, tags, flds] 注意，这里的ord是笔记类型中的卡片模板的索引号，
     * @param qfmt 格式化的问题字符串
     * @param afmt 格式化的答案字符串；
     * @return
     */
    public HashMap<String, String> _renderQA(Object[] data, String qfmt, String afmt) {
        // data is [cid, nid, mid, did, ord, tags, flds]
        // unpack fields and create dict，解包flds，生成字段对应值的数组flist
        String[] flist = Utils.splitFields((String) data[6]);
        // 这个集合fields将记录着，所有的字段名，和这个字段名对应的值；
        Map<String, String> fields = new HashMap<String, String>();
        // 获取笔记类型的json对象；model
        JSONObject model = mModels.get((Long) data[2]);
        /** 则返回的map集合的第一个元素就是下面的样式：
         *  { “正面” {0， {name:“正面”， sticky:false, rtl:false, ord:0, font:"Arial", size:20}}}
         *  { “背面” {1， {name:“背面”， sticky:false, rtl:false, ord:1, font:"Arial", size:20}}}
         * */
        Map<String, Pair<Integer, JSONObject>> fmap = mModels.fieldMap(model);
        // 为fields字段集合中的每个元素遍历赋值；
        for (String name : fmap.keySet()) {
            fields.put(name, flist[fmap.get(name).first]);
        }
        try {
            // 对于一个笔记，根据笔记类型中的卡片模板类型可以生成多张卡片，
            // 生成的卡片序号定义为，卡片模板类型的索引号加1；
            int cardNum = ((Integer) data[4]) + 1;
            // 向字段集合中添加Tags 元素；
            fields.put("Tags", ((String) data[5]).trim());
            // 向字段集合中添加类型元素，字段集合的类型，就是所在的笔记类型的名字；
            fields.put("Type", (String) model.get("name"));
            // 为这个字段集合，添加牌组属性，即这个记录属于哪个牌组的；
            fields.put("Deck", mDecks.name((Long) data[3]));
            // split参数中的-1，意思是；那么模式将被应用尽可能多的次数，而且数组可以是任何长度。
            // 即，此代码将会将当前的牌组名的每一级别名称分开形成以个数组；
            String[] parents = fields.get("Deck").split("::", -1);
            // 为这个字段集合，添加子牌组属性，并为其赋值，
            fields.put("Subdeck", parents[parents.length-1]);
            // 获取这张卡片将对应的卡片模板；
            JSONObject template;
            if (model.getInt("type") == Consts.MODEL_STD) {
                // 如果是笔记类型的类别是标准版的，则从笔记类型中获取相应的卡片模板；
                template = model.getJSONArray("tmpls").getJSONObject((Integer) data[4]);
            } else {
                // 如果不是标准版的，那就是填空题版的，取出笔记类型中的卡片模板的第一个模板，即可；
                template = model.getJSONArray("tmpls").getJSONObject(0);
            }
            // 字段集合中添加，一个Card的属性，它将记录着卡片用到的卡片模板的名字；
            fields.put("Card", template.getString("name"));
            // 把cardNum进行美国的本地化，然后输出前缀加字符c，生成字符串，c1,它将作为一个属性名
            // 用来记录该卡片已经生成过；
            fields.put(String.format(Locale.US, "c%d", cardNum), "1");
            // render q & a 渲染问题和答案
            HashMap<String, String> d = new HashMap<String, String>();
            // data is [cid, nid, mid, did, ord, tags, flds]
            // 获取卡片的id，并将其放入字典
            d.put("id", Long.toString((Long) data[0]));
            // 判断格式化过的问题，即形参qfmt字符串是否为空？如果为空，则，从卡片模板中获取qfmt,否则用形参提供的格式化过的问题；
            // 判断格式化过的答案，即形参afmt字符串是否为空？如果为空，怎，从卡片模板中获取afmt,否则用形参提供的格式化过的答案；
            qfmt = TextUtils.isEmpty(qfmt) ? template.getString("qfmt") : qfmt;
            afmt = TextUtils.isEmpty(afmt) ? template.getString("afmt") : afmt;
            // qfmt : "{{正面}}"
            // afmt : "{{FrontSide}}\n\n<hr id=answer>\n\n{{背面}}"
            // 创建一个配对对象数组；并且遍历这个配对数组；
            for (Pair<String, String> p : new Pair[]{new Pair<String, String>("q", qfmt), new Pair<String, String>("a", afmt)}) {
                String type = p.first;
                String format = p.second;
                if (type.equals("q")) {
                    format = fClozePatternQ.matcher(format).replaceAll(String.format(Locale.US, "{{$1cq-%d:", cardNum));
                    format = fClozeTagStart.matcher(format).replaceAll(String.format(Locale.US, "<%%cq:%d:", cardNum));
                } else {
                    format = fClozePatternA.matcher(format).replaceAll(String.format(Locale.US, "{{$1ca-%d:", cardNum));
                    format = fClozeTagStart.matcher(format).replaceAll(String.format(Locale.US, "<%%ca:%d:", cardNum));
                    // the following line differs from libanki // TODO: why?
                    fields.put("FrontSide", d.get("q")); // fields.put("FrontSide", mMedia.stripAudio(d.get("q")));
                }
                fields = (Map<String, String>) Hooks.runFilter("mungeFields", fields, model, data, this);
                // Todo_john 这里的Hooks与render（）还不明白，搁置，以后再细究吧！
                String html = new Template(format, fields).render();
                d.put(type, (String) Hooks.runFilter("mungeQA", html, type, fields, model, data, this));
                // empty cloze?
                if (type.equals("q") && model.getInt("type") == Consts.MODEL_CLOZE) {
                    if (getModels()._availClozeOrds(model, (String) data[6], false).size() == 0) {
                        String link = String.format("<a href=%s#cloze>%s</a>", Consts.HELP_SITE, "help");
                        d.put("q", String.format("Please edit this note and add some cloze deletions. (%s)", link));
                    }
                }
            }
            // 最终d中保存三个元素，它是个字典：具体内容：
            // "q"->"今天星期几啊"
            // "a"->"今天星期几呀\n\n<hr id=answer>\n\n星期三吧"
            // "id"->"41276839263"
            return d;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Return [cid, nid, mid, did, ord, tags, flds] db query
     */
    public ArrayList<Object[]> _qaData() {
        return _qaData("");
    }

    // 返回一个集合，集合中每个元素的内容为：cid, nid, mid, did, ord-template, tags, flds,
    public ArrayList<Object[]> _qaData(String where) {
        ArrayList<Object[]> data = new ArrayList<Object[]>();
        Cursor cur = null;
        try {
            cur = mDb.getDatabase().rawQuery(
                    "SELECT c.id, n.id, n.mid, c.did, c.ord, "
                            + "n.tags, n.flds FROM cards c, notes n WHERE c.nid == n.id " + where, null);
            while (cur.moveToNext()) {
                // data的内容即为： cid, nid, mid, did, ord-template, tags, flds,
                data.add(new Object[] { cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getLong(3), cur.getInt(4),
                        cur.getString(5), cur.getString(6) });
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        return data;
    }


    /**
     * Finding cards ************************************************************ ***********************************
     */

    /** Return a list of card ids
     * 搜索，返回含有search字符串的卡片集合；
     * */
    public List<Long> findCards(String search) {
        return new Finder(this).findCards(search, null);
    }


    /** Return a list of card ids 搜索，返回含有search字符串的卡片集合；*/
    public List<Long> findCards(String search, String order) {
        return new Finder(this).findCards(search, order);
    }


    public ArrayList<HashMap<String, String>> findCardsForCardBrowser(String search, boolean order, HashMap<String, String> deckNames) {
        return new Finder(this).findCardsForCardBrowser(search, order, deckNames);
    }


    /** Return a list of note ids */
    public List<Long> findNotes(String query) {
        return new Finder(this).findNotes(query);
    }


    public int findReplace(List<Long> nids, String src, String dst) {
        return Finder.findReplace(this, nids, src, dst);
    }


    public int findReplace(List<Long> nids, String src, String dst, boolean regex) {
        return Finder.findReplace(this, nids, src, dst, regex);
    }


    public int findReplace(List<Long> nids, String src, String dst, String field) {
        return Finder.findReplace(this, nids, src, dst, field);
    }


    public int findReplace(List<Long> nids, String src, String dst, boolean regex, String field, boolean fold) {
        return Finder.findReplace(this, nids, src, dst, regex, field, fold);
    }


    public List<Pair<String, List<Long>>> findDupes(String fieldName) {
        return Finder.findDupes(this, fieldName, "");
    }


    public List<Pair<String, List<Long>>> findDupes(String fieldName, String search) {
        return Finder.findDupes(this, fieldName, search);
    }


    /**
     * Stats ******************************************************************** ***************************
     */

    // cardstats
    // stats

    /**
     * Timeboxing *************************************************************** ********************************
     */

    public void setTimeLimit(long seconds) {
        try {
            mConf.put("timeLim", seconds);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    public long getTimeLimit() {
        long timebox = 0;
        try {
            timebox = mConf.getLong("timeLim");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return timebox;
    }


    public void startTimebox() {
        mStartTime = Utils.now();
        // Todo_john 这里的getReps()什么作用呢？
        mStartReps = mSched.getReps();
    }


    /* Return (elapsedTime, reps) if timebox reached, or null. */
    public Long[] timeboxReached() {
        try {
            if (mConf.getLong("timeLim") == 0) {
                // timeboxing disabled
                return null;
            }
            double elapsed = Utils.now() - mStartTime;
            if (elapsed > mConf.getLong("timeLim")) {
                // Todo_john 这里的getReps()什么作用呢？
                return new Long[] { mConf.getLong("timeLim"), (long) (mSched.getReps() - mStartReps) };
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return null;
    }


    /**
     * Undo ********************************************************************* **************************
     */

    /**
     * [type, undoName, data] type 1 = review; type 2 =
     */
    public void clearUndo() {
        mUndo = new LinkedList<Object[]>();
    }


    /** Undo menu item name, or "" if undo unavailable.
     * 返回undo名字，如果undo不可用，则这个名字为空“”，有了undo的名字，就系统就知道要撤销那个窗口的动作，
     * */
    public String undoName(Resources res) {
        if (mUndo.size() > 0) {
            int undoType = (Integer) mUndo.getLast()[0];
            if (undoType >= 0 && undoType < fUndoNames.length) {
                return res.getString(fUndoNames[undoType]);
            }
        }
        return "";
    }


    public boolean undoAvailable() {
        return mUndo.size() > 0;
    }

    // 执行撤销操作，
    public long undo() {
        // 获取要撤销的操作数据；
    	Object[] data = mUndo.removeLast();
    	switch ((Integer)data[0]) {
    	case UNDO_REVIEW: // 撤销上一次的复习卡片操作，
            // 获取上一次的卡片，
            Card c = (Card) data[1];
            // remove leech tag if it didn't have it before 删除它的水蛭标签
            Boolean wasLeech = (Boolean) data[2];
            if (!wasLeech && c.note().hasTag("leech")) {
                c.note().delTag("leech");
                c.note().flush();
            }
            // write old data
            c.flush(false);
            // and delete revlog entry 删除log记录
            long last = mDb.queryLongScalar("SELECT id FROM revlog WHERE cid = " + c.getId() + " ORDER BY id DESC LIMIT 1");
            mDb.execute("DELETE FROM revlog WHERE id = " + last);
            // restore any siblings 恢复任何一个兄弟姐妹，它的兄弟姐妹卡片被搁置的，现在都取消搁置，
            mDb.execute("update cards set queue=type,mod=?,usn=? where queue=-2 and nid=?",
                    new Object[]{ Utils.intNow(), usn(), c.getNid() });
            // and finally, update daily count 当前的卡片的队列是3吗？，如果不是就赋值为1，变成learning队列；
            int n = c.getQueue() == 3 ? 1 : c.getQueue();
            String type = (new String[] { "new", "lrn", "rev" })[n];
            // 把整张卡所在的牌组，以及这个牌组的所有父牌组的xxxxToday的第二个元素的值都累加1；
            mSched._updateStats(c, type, -1);
            // Todo_john 这里不是很懂啊，
            mSched.setReps(mSched.getReps() - 1);
            return c.getId();

    	case UNDO_BURY_NOTE: // 撤销上一次的搁置笔记操作
    		for (Card cc : (ArrayList<Card>)data[2]) {
    			cc.flush(false);
    		}
    		return (Long) data[3];

    	case UNDO_SUSPEND_CARD: // 撤销上一次暂停卡片操作
    		Card suspendedCard = (Card)data[1];
    		suspendedCard.flush(false);
    		return suspendedCard.getId();

    	case UNDO_SUSPEND_NOTE: // 撤销上一次的暂停笔记操作
    		for (Card ccc : (ArrayList<Card>) data[1]) {
    			ccc.flush(false);
    		}
    		return (Long) data[2];

    	case UNDO_DELETE_NOTE: // 撤销上一次的删除笔记操作
    		ArrayList<Long> ids = new ArrayList<Long>();
    		Note note2 = (Note)data[1];
    		note2.flush(note2.getMod(), false);
    		ids.add(note2.getId());
        		for (Card c4 : (ArrayList<Card>) data[2]) {
        			c4.flush(false);
    			ids.add(c4.getId());
        		}
    		mDb.execute("DELETE FROM graves WHERE oid IN " + Utils.ids2str(Utils.arrayList2array(ids)));
    		return (Long) data[3];

        case UNDO_BURY_CARD: // 撤销上一次的搁置卡片操作
            for (Card cc : (ArrayList<Card>)data[2]) {
                cc.flush(false);
            }
            return (Long) data[3];
        default:
        	return 0;
    	}
    }

    // 标记undo
    public void markUndo(int type, Object[] o) {
    	switch(type) {
    	case UNDO_REVIEW:
    		mUndo.add(new Object[]{type, ((Card)o[0]).clone(), o[1]});
    		break;
        case UNDO_BURY_NOTE:
            mUndo.add(new Object[]{type, o[0], o[1], o[2]});
            break;
        case UNDO_SUSPEND_CARD:
            mUndo.add(new Object[]{type, ((Card)o[0]).clone()});
            break;
        case UNDO_SUSPEND_NOTE:
            mUndo.add(new Object[]{type, o[0], o[1]});
            break;
    	case UNDO_DELETE_NOTE:
    		mUndo.add(new Object[]{type, o[0], o[1], o[2]});
    		break;
        case UNDO_BURY_CARD:
            mUndo.add(new Object[]{type, o[0], o[1], o[2]});
            break;
    	}
    	while (mUndo.size() > UNDO_SIZE_MAX) {
    		mUndo.removeFirst();
    	}
    }

    //todo_john 这里的markReview与对卡片进行标记不同，没回答完一个卡片，系统都会mark一次，这是对学习过的卡片进行记录，用于撤销操作，
    public void markReview(Card card) {
        markUndo(UNDO_REVIEW, new Object[]{card, card.note().hasTag("leech")});
    }

    /**
     * DB maintenance *********************************************************** ************************************
     */


    /*
     * Basic integrity check for syncing. True if ok.
     * 基本的完整检查，为同步；
     */
    public boolean basicCheck() {
        // cards without notes 是否存在那种没有笔记记录的卡片；
        if (mDb.queryScalar("select 1 from cards where nid not in (select id from notes) limit 1") > 0) {
            return false;
        }
        // 检查一下有没有坏笔记，即所有卡片中都没有用到做的笔记；
        boolean badNotes = mDb.queryScalar(String.format(Locale.US,
                // select distinct nid from cards作用是过滤掉结果集中的重复值，
                "select 1 from notes where id not in (select distinct nid from cards) " +
                "or mid not in %s limit 1", Utils.ids2str(mModels.ids()))) > 0;
        // notes without cards or models
        if (badNotes) {
            return false;
        }
        try {
            // invalid ords
            for (JSONObject m : mModels.all()) {
                // ignore clozes 忽略填空题类型的笔记类型；
                if (m.getInt("type") != Consts.MODEL_STD) {
                    continue;
                }
                // Make a list of valid ords for this model
                JSONArray tmpls = m.getJSONArray("tmpls");
                int[] ords = new int[tmpls.length()];
                for (int t = 0; t < tmpls.length(); t++) {
                    ords[t] = tmpls.getJSONObject(t).getInt("ord");
                }

                // 检验当前的ords中包含的卡片模板是否都被用过，
                boolean badOrd = mDb.queryScalar(String.format(Locale.US,
                        "select 1 from cards where ord not in %s and nid in ( " +
                        "select id from notes where mid = %d) limit 1",
                        Utils.ids2str(ords), m.getLong("id"))) > 0;
                if (badOrd) {
                    return false;
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return true;
    }


    /** Fix possible problems and rebuild caches.
     * 修复可能出现的问题，重新创建缓存
     * */
    public long fixIntegrity() {
        // mPath就是~/AnkiDroid/collection.anki2
        File file = new File(mPath);
        ArrayList<String> problems = new ArrayList<String>();
        long oldSize = file.length();
        try {
            mDb.getDatabase().beginTransaction();
            try {
                save(); //在此提交数据库；
                // PRAGMA integrity_check 该命令对整个数据库进行完整性检查
                if (!mDb.queryString("PRAGMA integrity_check").equals("ok")) {
                    return -1;
                }
                // note types with a missing model
                // 检查是否有那种笔记，即，只有笔记，而没有笔记类型的笔记；
                ArrayList<Long> ids = mDb.queryColumn(Long.class,
                        "SELECT id FROM notes WHERE mid NOT IN " + Utils.ids2str(mModels.ids()), 0);
                if (ids.size() != 0) {
                	problems.add("Deleted " + ids.size() + " note(s) with missing note type.");
	                _remNotes(Utils.arrayList2array(ids));
                }
                // for each model
                for (JSONObject m : mModels.all()) {
                    // cards with invalid ordinal
                    if (m.getInt("type") == Consts.MODEL_STD) {
                        ArrayList<Integer> ords = new ArrayList<Integer>();
                        JSONArray tmpls = m.getJSONArray("tmpls");
                        for (int t = 0; t < tmpls.length(); t++) {
                            ords.add(tmpls.getJSONObject(t).getInt("ord"));
                        }
                        ids = mDb.queryColumn(Long.class,
                                "SELECT id FROM cards WHERE ord NOT IN " + Utils.ids2str(ords) + " AND nid IN ( " +
                                "SELECT id FROM notes WHERE mid = " + m.getLong("id") + ")", 0);
                        if (ids.size() > 0) {
                            problems.add("Deleted " + ids.size() + " card(s) with missing template.");
                            remCards(Utils.arrayList2array(ids));
                        }
                    }
                    // notes with invalid field counts
                    ids = new ArrayList<Long>();
                    Cursor cur = null;
                    try {
                        cur = mDb.getDatabase().rawQuery("select id, flds from notes where mid = " + m.getLong("id"), null);
                        while (cur.moveToNext()) {
                            String flds = cur.getString(1);
                            long id = cur.getLong(0);
                            int fldsCount = 0;
                            for (int i = 0; i < flds.length(); i++) {
                                if (flds.charAt(i) == 0x1f) {
                                    fldsCount++;
                                }
                            }
                            if (fldsCount + 1 != m.getJSONArray("flds").length()) {
                                ids.add(id);
                            }
                        }
                        if (ids.size() > 0) {
                            problems.add("Deleted " + ids.size() + " note(s) with wrong field count.");
                            _remNotes(Utils.arrayList2array(ids));
                        }
                    } finally {
                        if (cur != null && !cur.isClosed()) {
                            cur.close();
                        }
                    }
                }
                // delete any notes with missing cards 删除那些没有卡片的笔记，
                ids = mDb.queryColumn(Long.class,
                        "SELECT id FROM notes WHERE id NOT IN (SELECT DISTINCT nid FROM cards)", 0);
                if (ids.size() != 0) {
                	problems.add("Deleted " + ids.size() + " note(s) with missing no cards.");
	                _remNotes(Utils.arrayList2array(ids));
                }
                // cards with missing notes 删除那些没有笔记的卡片
                ids = mDb.queryColumn(Long.class,
                        "SELECT id FROM cards WHERE nid NOT IN (SELECT id FROM notes)", 0);
                if (ids.size() != 0) {
                    problems.add("Deleted " + ids.size() + " card(s) with missing note.");
                    remCards(Utils.arrayList2array(ids));
                }
                // cards with odue set when it shouldn't be
                ids = mDb.queryColumn(Long.class,
                        "select id from cards where odue > 0 and (type=1 or queue=2) and not odid", 0);
                if (ids.size() != 0) {
                    problems.add("Fixed " + ids.size() + " card(s) with invalid properties.");
                    mDb.execute("update cards set odue=0 where id in " + Utils.ids2str(ids));
                }
                // cards with odid set when not in a dyn deck
                ArrayList<Long> dids = new ArrayList<Long>();
                for (long id : mDecks.allIds()) {
                    if (!mDecks.isDyn(id)) {
                        dids.add(id);
                    }
                }
                ids = mDb.queryColumn(Long.class,
                        "select id from cards where odid > 0 and did in " + Utils.ids2str(dids), 0);
                if (ids.size() != 0) {
                    problems.add("Fixed " + ids.size() + " card(s) with invalid properties.");
                    mDb.execute("update cards set odid=0, odue=0 where id in " + Utils.ids2str(ids));
                }
                // tags
                mTags.registerNotes();
                // field cache
                for (JSONObject m : mModels.all()) {
                    updateFieldCache(Utils.arrayList2array(mModels.nids(m)));
                }
                // new cards can't have a due position > 32 bits
                mDb.execute("UPDATE cards SET due = 1000000, mod = " + Utils.intNow() + ", usn = " + usn()
                        + " WHERE due > 1000000 AND queue = 0");
                // new card position
                mConf.put("nextPos", mDb.queryScalar("SELECT max(due) + 1 FROM cards WHERE type = 0"));
                // reviews should have a reasonable due
                ids = mDb.queryColumn(Long.class, "SELECT id FROM cards WHERE queue = 2 AND due > 10000", 0);
                if (ids.size() > 0) {
                	problems.add("Reviews had incorrect due date.");
                    mDb.execute("UPDATE cards SET due = 0, mod = " + Utils.intNow() + ", usn = " + usn()
                            + " WHERE id IN " + Utils.ids2str(Utils.arrayList2array(ids)));
                }
                mDb.getDatabase().setTransactionSuccessful();
                // DB must have indices. Older versions of AnkiDroid didn't create them for new collections.
                int ixs = mDb.queryScalar("select count(name) from sqlite_master where type = 'index'");
                if (ixs < 7) {
                    problems.add("Indices were missing.");
                    Storage.addIndices(mDb);
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            } finally {
                mDb.getDatabase().endTransaction();
            }
        } catch (RuntimeException e) {
            Timber.e(e, "doInBackgroundCheckDatabase - RuntimeException on marking card");
            AnkiDroidApp.sendExceptionReport(e, "doInBackgroundCheckDatabase");
            return -1;
        }
        // and finally, optimize
        optimize();
        file = new File(mPath);
        long newSize = file.length();
        // if any problems were found, force a full sync
        if (problems.size() > 0) {
            modSchemaNoCheck();
        }
        // TODO: report problems
        return (long) ((oldSize - newSize) / 1024);
    }


    public void optimize() {
        Timber.i("executing VACUUM statement");
        mDb.execute("VACUUM");
        Timber.i("executing ANALYZE statement");
        mDb.execute("ANALYZE");
    }


    /**
     * Logging
     * ***********************************************************
     * 例如： mCol.log(mToday, mDayCutoff);
     */

    public void log(Object... args) {
        if (!mDebugLog) {
            return;
        }
        StackTraceElement trace = Thread.currentThread().getStackTrace()[3];
        // Overwrite any args that need special handling for an appropriate string representation
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof long[]) {
                args[i] = Arrays.toString((long []) args[i]);
            }
        }
        String s = String.format("[%s] %s:%s(): %s", Utils.intNow(), trace.getFileName(), trace.getMethodName(),
                TextUtils.join(",  ", args));
        mLogHnd.println(s);
        Timber.d(s);
    }


    private void _openLog() {
        if (!mDebugLog) {
            return;
        }
        try {
            File lpath = new File(mPath.replaceFirst("\\.anki2$", ".log"));
            if (lpath.exists() && lpath.length() > 10*1024*1024) {
                File lpath2 = new File(lpath + ".old");
                if (lpath2.exists()) {
                    lpath2.delete();
                }
                lpath.renameTo(lpath2);
            }
            mLogHnd = new PrintWriter(new BufferedWriter(new FileWriter(lpath, true)), true);
        } catch (IOException e) {
            // turn off logging if we can't open the log file
            Timber.e("Failed to open collection.log file - disabling logging");
            mDebugLog = false;
        }
    }


    private void _closeLog() {
        if (mLogHnd != null) {
            mLogHnd.close();
            mLogHnd = null;
        }
    }

    /**
     * Getters/Setters ********************************************************** *************************************
     */

    public AnkiDb getDb() {
        return mDb;
    }


    public Decks getDecks() {
        return mDecks;
    }


    public Media getMedia() {
        return mMedia;
    }


    public Models getModels() {
        return mModels;
    }

    /** Check if this collection is valid. */
    public boolean validCollection() {
    	//TODO: more validation code
    	return mModels.validateModel();
    }

    public JSONObject getConf() {
        return mConf;
    }


    public void setConf(JSONObject conf) {
        mConf = conf;
    }


    public long getScm() {
        return mScm;
    }


    public void setScm(long scm) {
        mScm = scm;
    }


    public boolean getServer() {
        return mServer;
    }


    public void setLs(long ls) {
        mLs = ls;
    }


    public long getLs() {
        return mLs;
    }


    public void setUsnAfterSync(int usn) {
        mUsn = usn;
    }


    public long getMod() {
        return mMod;
    }


    /* this getter is only for syncing routines, use usn() instead elsewhere */
    public int getUsnForSync() {
        return mUsn;
    }


    public Tags getTags() {
        return mTags;
    }


    public long getCrt() {
        return mCrt;
    }


    public void setCrt(long crt) {
        mCrt = crt;
    }


    public Sched getSched() {
        return mSched;
    }


    public String getPath() {
        return mPath;
    }


    public void setServer(boolean server) {
        mServer = server;
    }

    public boolean getDirty() {
        return mDty;
    }

}
