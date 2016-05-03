/****************************************************************************************
 * Copyright (c) 2009 Daniel Svärd <daniel.svard@gmail.com>                             *
 * Copyright (c) 2010 Rick Gruber-Riemer <rick@vanosten.net>                            *
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2011 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Pair;

import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.libanki.template.Template;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Models {
    // 下面三个是用来匹配填空题类型的卡片的正则表达式；
    // faa{{addcloze:fa?:dddd?:fr}}fdas---从中筛选出 {{addcloze:fa?:dddd?:fr}}
    private static final Pattern fClozePattern1 = Pattern.compile("\\{\\{[^}]*?cloze:(?:[^}]?:)*(.+?)\\}\\}");
    // dfafd<%cloze:fafdafa%>afa---从中筛选出<%cloze:fafdafa%>
    private static final Pattern fClozePattern2 = Pattern.compile("<%cloze:(.+?)%>");
    // faa{{c233::fasfafe}}fdas---从中筛选出{{c233::fasfafe}}
    private static final Pattern fClozeOrdPattern = Pattern.compile("\\{\\{c(\\d+)::.+?\\}\\}");

    // 这个是默认笔记类型；
    public static final String defaultModel =
              "{'sortf': 0, "
            + "'did': 1, "
            + "'latexPre': \""
            + "\\\\documentclass[12pt]{article}\\n"
            + "\\\\special{papersize=3in,5in}\\n"
            + "\\\\usepackage[utf8]{inputenc}\\n"
            + "\\\\usepackage{amssymb,amsmath}\\n"
            + "\\\\pagestyle{empty}\\n"
            + "\\\\setlength{\\\\parindent}{0in}\\n"
            + "\\\\begin{document}\\n"
            + "\", "
            + "'latexPost': \"\\\\end{document}\", "
            + "'mod': 0, "
            + "'usn': 0, "
            + "'vers': [], " // FIXME: remove when other clients have caught up
            + "'type': "
            + Consts.MODEL_STD
            + ", "
            + "'css': \".card {\\n"
            + " font-familiy: arial;\\n"
            + " font-size: 20px;\\n"
            + " text-align: center;\\n"
            + " color: black;\\n"
            + " background-color: white;\\n"
            + "}\""
            + "}";
    // 默认的字段样式；
    private static final String defaultField = "{'name': \"\", " + "'ord': null, " + "'sticky': False, " +
    // the following alter editing, and are used as defaults for the template wizard
            // 以下的编辑，用来做默认字段模板制作向导
            "'rtl': False, " + "'font': \"Arial\", " + "'size': 20, " +
            // reserved for future use 保留字段，未来可能使用到；
            "'media': [] }";
    // 默认卡片模板；
    private static final String defaultTemplate = "{'name': \"\", " + "'ord': null, " + "'qfmt': \"\", "
            + "'afmt': \"\", " + "'did': null, " + "'bqfmt': \"\"," + "'bafmt': \"\"," + "'bfont': \"Arial\"," +
            "'bsize': 12 }";

    // /** Regex pattern used in removing tags from text before diff */
    // 这些正则表达式，当你从文本中删除标签的时候用得到；
    // private static final Pattern sFactPattern = Pattern.compile("%\\([tT]ags\\)s");
    // private static final Pattern sModelPattern = Pattern.compile("%\\(modelTags\\)s");
    // private static final Pattern sTemplPattern = Pattern.compile("%\\(cardModel\\)s");

    private Collection mCol;
    // 当前笔记类型是否被改变过；
    private boolean mChanged;
    // 笔记模板，它将记录着整个collection的所有笔记类型，并保存在一个map集合中；
    private HashMap<Long, JSONObject> mModels;

    // BEGIN SQL table entries
    // 笔记类型的Id
    private int mId;
    // 笔记类型的名字；
    private String mName = "";
    // 创建时间；
    private long mCrt = Utils.intNow();
    // 修改时间
    private long mMod = Utils.intNow();
    // Todo_john 这个参数 mConf没用到过；
    private JSONObject mConf;
    // Todo_john 这个参数 mCss也没用到过；
    private String mCss = "";
    // Todo_john 这个参数还是没用用到过；
    private JSONArray mFields;
    // Todo_john 这个参数还是没用用到过；
    private JSONArray mTemplates;
    // BEGIN SQL table entries

    // private Decks mDeck;
    // private AnkiDb mDb;
    //
    /** Map for compiled Mustache Templates */
    // Todo_john 编译模板的集合，不懂；看看再说；从未用到过；
    private Map<String, Template> mCmpldTemplateMap = new HashMap<String, Template>();


    //
    // /** Map for convenience and speed which contains FieldNames from current model */
    // private TreeMap<String, Integer> mFieldMap = new TreeMap<String, Integer>();
    //
    // /** Map for convenience and speed which contains Templates from current model */
    // private TreeMap<Integer, JSONObject> mTemplateMap = new TreeMap<Integer, JSONObject>();
    //
    // /** Map for convenience and speed which contains the CSS code related to a Template */
    // private HashMap<Integer, String> mCssTemplateMap = new HashMap<Integer, String>();
    //
    // /**
    // * The percentage chosen in preferences for font sizing at the time when the css for the CardModels related to
    // this
    // * Model was calculated in prepareCSSForCardModels.
    // */
    // private transient int mDisplayPercentage = 0;
    // private boolean mNightMode = false;

    /**
     * Saving/loading registry
     * ***********************************************************************************************
     */
    // 看来，笔记类型等价于集合，它只有与集合绑定在一起，再有意义，因此，构造方法定义出集合即可；
    public Models(Collection col) {
        mCol = col;
    }


    /**
     * Load registry from JSON.
     * 由json字符串生成mModels笔记类型的集合；
     */
    public void load(String json) {
        mChanged = false;
        mModels = new HashMap<Long, JSONObject>();
        try {
            JSONObject modelarray = new JSONObject(json);
            JSONArray ids = modelarray.names();
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.getString(i);
                    JSONObject o = modelarray.getJSONObject(id);
                    mModels.put(o.getLong("id"), o);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Mark M modified if provided, and schedule registry flush.
     */
    public void save() {
        save(null, false);
    }

    // 保存笔记类型；
    public void save(JSONObject m) {
        save(m, false);
    }

    /**
     * Save a model 保存一个笔记类型；
     * @param m model to save
     * @param templates flag which (when true) re-generates the cards for each note which uses the model
     * 由此可以看出，模型和模板的不同之处，模型即笔记类型，它包含笔记的概念，包含笔记字段的概念，包含卡片模板的
     * 概念，这里的卡片模板旗标，用到当前笔记类型的笔记，是否要再次生成相应的卡片呢？
     * 如果是，则，用到此笔记类型的笔记，将再次按照笔记类型中指定的卡片模板生成相应卡片；
     * 一句话总结： 此方法作用是：
     */
    public void save(JSONObject m, boolean templates) {
        if (m != null && m.has("id")) {
            try {
                // 为这个笔记类型设置最后修改时间 mod字段；
                m.put("mod", Utils.intNow());
                // 为这个笔记类型设置 usn 属性值，这个属性值来自于collection
                m.put("usn", mCol.usn());
                // TODO: fix empty id problem on _updaterequired (needed for model adding)
                if (m.getLong("id") != 0) {
                    // 更新笔记类型的req属性，这个属性记录着每个卡片模板的正面所需的字段信息；
                    _updateRequired(m);
                }
                if (templates) {
                    // 为每用到这个笔记类型的笔记，重新生成卡片；
                    _syncTemplates(m);
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        mChanged = true;
        // The following hook rebuilds the tree in the Anki Desktop browser -- we don't need it
        // runHook("newModel")
    }


    /**
     * Flush the registry if any models were changed.
     * 将改变过的model写到数据库中；
     */
    public void flush() {
        if (mChanged) {
            JSONObject array = new JSONObject();
            try {
                for (Map.Entry<Long, JSONObject> o : mModels.entrySet()) {
                    array.put(Long.toString(o.getKey()), o.getValue());
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            ContentValues val = new ContentValues();
            val.put("models", Utils.jsonToString(array));
            mCol.getDb().update("col", val);
            mChanged = false;
        }
    }


    /**
     * Retrieving and creating models
     * 检索和创建笔记类型
     * ***********************************************************************************************
     */

    /**
     * Get current model.获取当前的笔记类型
     * @return The JSONObject of the model, or null if not found in the deck and in the configuration.
     */
    public JSONObject current() {
        return current(true);
    }

    /**
     * Get current model.获取当前牌组（记忆库）的笔记类型；
     * @param forDeck If true, it tries to get the deck specified in deck by mid, otherwise or if the former is not
     *                found, it uses the configuration`s field curModel.
     * @return The JSONObject of the model, or null if not found in the deck and in the configuration.
     */
    public JSONObject current(boolean forDeck) {
        JSONObject m = null;
        if (forDeck) {
            // 通过当前牌组获得当前笔记类型；
            m = get(mCol.getDecks().current().optLong("mid", -1));
        }
        if (m == null) {
            // 如果不能够从当前牌组中获得笔记类型，则可以通过col中的conf字段的属性值
            // curModel来确定当前笔记类型；
            m = get(mCol.getConf().optLong("curModel", -1));
        }
        if (m == null) {
            // 如果还得不到当前笔记类型，则可以取出笔记类型集合，并取出集合中的第一个笔记类型；
            if (!mModels.isEmpty()) {
                m = mModels.values().iterator().next();
            }
        }
        return m;
    }

    // 设置当前笔记类型；拿出col中的conf字段，为其curModel属性赋值，
    public void setCurrent(JSONObject m) {
        try {
            mCol.getConf().put("curModel", m.get("id"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        mCol.setMod();
    }


    /** get model with ID, or none.
     * 通过id获取笔记类型；
     * */
    public JSONObject get(long id) {
        if (mModels.containsKey(id)) {
            return mModels.get(id);
        } else {
            return null;
        }
    }


    /** get all models
     * 获取所有的笔记类型，返回一个承载json对象的集合；
     * */
    public ArrayList<JSONObject> all() {
        ArrayList<JSONObject> models = new ArrayList<JSONObject>();
        Iterator<JSONObject> it = mModels.values().iterator();
        while (it.hasNext()) {
            models.add(it.next());
        }
        return models;
    }


    /** get model with NAME.
     * 通过笔记类型的名字来获取json对象类型的笔记类型；
     * */
    public JSONObject byName(String name) {
        for (JSONObject m : mModels.values()) {
            try {
                if (m.getString("name").equals(name)) {
                    return m;
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }


    /** Create a new model, save it in the registry, and return it.
     * 创建一个新的笔记类型；并以json对象类型返回；
     * */
    public JSONObject newModel(String name) {
        // caller should call save() after modifying
        JSONObject m;
        try {
            m = new JSONObject(defaultModel);
            m.put("name", name);
            m.put("mod", Utils.intNow());
            m.put("flds", new JSONArray());
            m.put("tmpls", new JSONArray());
            m.put("tags", new JSONArray());
            m.put("id", 0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return m;
    }


    /** Delete model, and all its cards/notes.
     * 删除一个笔记类型，连带使用这个笔记类型的note,以及card都删除掉；
     * @throws ConfirmModSchemaException */
    public void rem(JSONObject m) throws ConfirmModSchemaException {
        // 修改col中的schema 用于强制同步；
        mCol.modSchema(true);
        try {
            long id = m.getLong("id");
            // 当前的笔记类型是要删除的笔记类型吗？
            boolean current = current().getLong("id") == id;
            // delete notes/cards 删除笔记和卡片
            mCol.remCards(Utils.arrayList2array(mCol.getDb().queryColumn(Long.class,
                    // 从数据库中筛选出用到这个笔记类型的笔记，然后衰选出用到这些笔记的卡片，统统删除；
                    "SELECT id FROM cards WHERE nid IN (SELECT id FROM notes WHERE mid = " + id + ")", 0)));
            // then the model，删除个这笔记类型；
            mModels.remove(id);
            // 相当于save(null, false) 作用在于，标记mChanged==true;即，标记Model这个对象已被修改过；
            save();
            // GUI should ensure last model is not deleted
            // 如果当前的笔记类型，就是要删除的笔记类型，则删除完之后把当前的笔记类型设置为笔记类型集合中的第一个值；
            if (current) {
                setCurrent(mModels.values().iterator().next());
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 添加一个笔记类型；
    public void add(JSONObject m) {
        // 为m这个笔记类型设置个唯一的id ;
        _setID(m);
        // 更新mModel，向这个集合中添加一个新的笔记类型元素 m
        update(m);
        // 设置m 为当前的笔记类型；
        setCurrent(m);
        // 保存m,
        save(m);
    }


    /** Add or update an existing model. Used for syncing and merging.
     * 向mModel这个集合中添加一个新的 笔记类型 model 元素；
     * */
    public void update(JSONObject m) {
        try {
            mModels.put(m.getLong("id"), m);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        // mark registry changed, but don't bump mod time
        save();
    }

    // 为 笔记类型这个对象设置一个id，唯一的；
    private void _setID(JSONObject m) {
        long id = Utils.intNow(1000);
        while (mModels.containsKey(id)) {
            id = Utils.intNow(1000);
        }
        try {
            m.put("id", id);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 是否包含参数中id值得笔记模型？
    public boolean have(long id) {
        return mModels.containsKey(id);
    }

    // 当前牌组集合中所有笔记类型的id组成的数组
    public long[] ids() {
        Iterator<Long> it = mModels.keySet().iterator();
        long[] ids = new long[mModels.size()];
        int i = 0;
        while (it.hasNext()) {
            ids[i] = it.next();
            i++;
        }
        return ids;
    }


    /**
     * Tools ***********************************************************************************************
     */

    /** Note ids for M
     * 返回所有用到此笔记类型的笔记
     * */
    public ArrayList<Long> nids(JSONObject m) {
        try {
            return mCol.getDb().queryColumn(Long.class, "SELECT id FROM notes WHERE mid = " + m.getLong("id"), 0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Number of notes using m 用到此笔记类型的笔记总条数；
     * @param m The model to the count the notes of.
     * @return The number of notes with that model.
     */
    public int useCount(JSONObject m) {
        try {
            return mCol.getDb().queryScalar("select count() from notes where mid = " + m.getLong("id"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Number of notes using m 用到指定笔记类型，并且使用其中一个卡片模板而生成的卡片的数量；
     * @param m The model to the count the notes of.
     * @param ord The index of the card template
     * @return The number of notes with that model.
     */
    public int tmplUseCount(JSONObject m, int ord) {
        try {
            return mCol.getDb().queryScalar("select count() from cards, notes where cards.nid = notes.id and notes.mid = " + m.getLong("id") + " and cards.ord = " + ord);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Copying ***********************************************************************************************
     */

    /** Copy, save and return.
     * 拷贝一个笔记类型；
     * */
    public JSONObject copy(JSONObject m) {
        JSONObject m2 = null;
        try {
            m2 = new JSONObject(Utils.jsonToString(m));
            m2.put("name", m2.getString("name") + " copy");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        add(m2);
        return m2;
    }


    /**
     * Fields ***********************************************************************************************
     * 添加一个新的字段，字段名字为name;
     */

    public JSONObject newField(String name) {
        JSONObject f;
        try {
            f = new JSONObject(defaultField);
            f.put("name", name);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return f;
    }


    /** "Mapping of field name -> (ord, field).
     *  比如： 字段有“正面”和“背面”，正面是第一个字段，背面是第二个字段；
     *  则返回的map集合的第一个元素就是下面的样式：
     *  { “正面” {0， {name:“正面”， sticky:false, rtl:false, ord:0, font:"Arial", size:20}}}
     *  { “背面” {1， {name:“背面”， sticky:false, rtl:false, ord:1, font:"Arial", size:20}}}
     * */
    public Map<String, Pair<Integer, JSONObject>> fieldMap(JSONObject m) {
        JSONArray ja;
        try {
            ja = m.getJSONArray("flds");
            // TreeMap<Integer, String> map = new TreeMap<Integer, String>();
            Map<String, Pair<Integer, JSONObject>> result = new HashMap<String, Pair<Integer, JSONObject>>();
            for (int i = 0; i < ja.length(); i++) {
                JSONObject f = ja.getJSONObject(i);
                result.put(f.getString("name"), new Pair<Integer, JSONObject>(f.getInt("ord"), f));
            }
            return result;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 返回包含字段名字的集合；
    public ArrayList<String> fieldNames(JSONObject m) {
        JSONArray ja;
        try {
            ja = m.getJSONArray("flds");
            ArrayList<String> names = new ArrayList<String>();
            for (int i = 0; i < ja.length(); i++) {
                names.add(ja.getJSONObject(i).getString("name"));
            }
            return names;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

    }

    // 返回笔记类型的排序字段，即这个笔记类型下的笔记，将以那个字段进行排序
    public int sortIdx(JSONObject m) {
        try {
            return m.getInt("sortf");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 设置笔记类型的排序字段；
    public void setSortIdx(JSONObject m, int idx) throws ConfirmModSchemaException{
        try {
            // 标记修改过的schema，用于强制同步；
            mCol.modSchema(true);
            m.put("sortf", idx);
            mCol.updateFieldCache(Utils.toPrimitive(nids(m)));
            save(m);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 为一个笔记类型添加新的字段；
    public void addField(JSONObject m, JSONObject field) throws ConfirmModSchemaException {
        // only mod schema if model isn't new
        try {
            if (m.getLong("id") != 0) {
                mCol.modSchema(true);
            }
            JSONArray ja = m.getJSONArray("flds");
            ja.put(field);
            m.put("flds", ja);
            // 也可称为跟新修改每个字段的索引
            _updateFieldOrds(m);
            save(m);
            _transformFields(m, new TransformFieldAdd());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 创建一个新的字段数组，新的字段数组从传进来的字段数组中拷贝元素过来，并在最后添加一个新的控元素；
     */
    class TransformFieldAdd implements TransformFieldVisitor {
        @Override
        /**
         * 创建一个新的字段数组，新的字段数组从传进来的字段数组中拷贝元素过来，并在最后添加一个新的控元素；
         */
        public String[] transform(String[] fields) {
            String[] f = new String[fields.length + 1];
            System.arraycopy(fields, 0, f, 0, fields.length);
            f[fields.length] = "";
            return f;
        }
    }


    public void remField(JSONObject m, JSONObject field) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        try {
            JSONArray ja = m.getJSONArray("flds");
            JSONArray ja2 = new JSONArray();
            int idx = -1;
            for (int i = 0; i < ja.length(); ++i) {
                if (field.equals(ja.getJSONObject(i))) {
                    idx = i;
                    continue;
                }
                ja2.put(ja.get(i));
            }
            m.put("flds", ja2);
            int sortf = m.getInt("sortf");
            if (sortf >= m.getJSONArray("flds").length()) {
                m.put("sortf", sortf - 1);
            }
            _updateFieldOrds(m);
            _transformFields(m, new TransformFieldDelete(idx));
            if (idx == sortIdx(m)) {
                // need to rebuild
                mCol.updateFieldCache(Utils.toPrimitive(nids(m)));
            }
            renameField(m, field, null);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 创建一个新的字段集合，此字段集合从形参中的字段数组转换而来，
     * 从这个新建立的字段数组中删除指定索引的那个字段，
     * 删除后将新的字段集合变成数组，将其返回
     */
    class TransformFieldDelete implements TransformFieldVisitor {
        private int idx;


        public TransformFieldDelete(int _idx) {
            idx = _idx;
        }


        @Override
        /**
         * 创建一个新的字段集合，此字段集合从形参中的字段数组转换而来，
         * 从这个新建立的字段数组中删除指定索引的那个字段，
         * 删除后将新的字段集合变成数组，将其返回
         */
        public String[] transform(String[] fields) {
            ArrayList<String> fl = new ArrayList<String>(Arrays.asList(fields));
            fl.remove(idx);
            return fl.toArray(new String[fl.size()]);
        }
    }

    /**
     * 移动字段集合中，有个字段的位置，
     * @param m 笔记类型model
     * @param field 某个字段的具体内容，
     * @param idx 字段的索引
     * @throws ConfirmModSchemaException
     */
    public void moveField(JSONObject m, JSONObject field, int idx) throws ConfirmModSchemaException {
        // 标记schema改变，用来强制同步
        mCol.modSchema(true);
        try {
            JSONArray ja = m.getJSONArray("flds");
            // l用来承载新的flds内容，
            ArrayList<JSONObject> l = new ArrayList<JSONObject>();
            int oldidx = -1;
            for (int i = 0; i < ja.length(); ++i) {
                l.add(ja.getJSONObject(i));
                if (field.equals(ja.getJSONObject(i))) {
                    oldidx = i;
                    if (idx == oldidx) {
                        return;
                    }
                }
            }
            // remember old sort field 记录下用于排序的字段，并且取出来将其变成字符串；
            String sortf = Utils.jsonToString(m.getJSONArray("flds").getJSONObject(m.getInt("sortf")));
            // move
            l.remove(oldidx);
            l.add(idx, field);
            // 更新medol中flds属性的值；
            m.put("flds", new JSONArray(l));
            // restore sort field
            ja = m.getJSONArray("flds");
            // 重新对model中的排序字段赋值；
            for (int i = 0; i < ja.length(); ++i) {
                if (Utils.jsonToString(ja.getJSONObject(i)).equals(sortf)) {
                    m.put("sortf", i);
                    break;
                }
            }
            // 更新每个字段的索引号
            _updateFieldOrds(m);
            // 保存这个笔记类型；
            save(m);
            //在数据库中更新此model涉及的notes记录；
            _transformFields(m, new TransformFieldMove(idx, oldidx));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 移动字典数组中的位置，即，把某个字段放到新的索引位置，其他的字段怎么变动不用管；
     */
    class TransformFieldMove implements TransformFieldVisitor {
        private int idx;
        private int oldidx;


        public TransformFieldMove(int _idx, int _oldidx) {
            idx = _idx;
            oldidx = _oldidx;
        }


        @Override
        /**
         * 移动字典数组中的位置，即，把某个字段放到新的索引位置，其他的字段怎么变动不用管；
         */
        public String[] transform(String[] fields) {
            String val = fields[oldidx];
            ArrayList<String> fl = new ArrayList<String>(Arrays.asList(fields));
            fl.remove(oldidx);
            fl.add(idx, val);
            return fl.toArray(new String[fl.size()]);
        }
    }


    // 对笔记类型中的m，中的字段field，重新命名；
    public void renameField(JSONObject m, JSONObject field, String newName) throws ConfirmModSchemaException {
        // 标记一个修改过的schema，用于强制同步；
        mCol.modSchema(true);
        try {
            // Todo_john Pattern.quote(field.getString("name"))的结果是 "\Q"+name+"\E", 但这是为什么呢？
            String pat = String.format("\\{\\{([^{}]*)([:#^/]|[^:#/^}][^:}]*?:|)%s\\}\\}",
                    Pattern.quote(field.getString("name")));
            if (newName == null) {
                newName = "";
            }
            String repl = "{{$1$2" + newName + "}}";

            JSONArray tmpls = m.getJSONArray("tmpls");
            // 更新所有卡片模板中的字段名
            for (int i = 0; i < tmpls.length(); ++i) {
                JSONObject t = tmpls.getJSONObject(i);
                for (String fmt : new String[] { "qfmt", "afmt" }) {
                    if (!newName.equals("")) {
                        t.put(fmt, t.getString(fmt).replaceAll(pat, repl));
                    } else {
                        t.put(fmt, t.getString(fmt).replaceAll(pat, ""));
                    }
                }
            }
            field.put("name", newName);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        save(m);
    }

    // 更新笔记类型中字段属性下的所有字段的顺序号，也可称为跟新修改每个字段的索引
    public void _updateFieldOrds(JSONObject m) {
        JSONArray ja;
        try {
            ja = m.getJSONArray("flds");
            for (int i = 0; i < ja.length(); i++) {
                JSONObject f = ja.getJSONObject(i);
                f.put("ord", i);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 这是个接口，可理解为匿名分类；它的作用是--字段迁移参观者；
    interface TransformFieldVisitor {
        public String[] transform(String[] fields);
    }


    // 更新这个笔记类型model所涉及的笔记，特别是字段flds的修改，并将此更新写入数据库notes表中；
    public void _transformFields(JSONObject m, TransformFieldVisitor fn) {
        // model hasn't been added yet?
        try {
            if (m.getLong("id") == 0) {
                return;
            }
            ArrayList<Object[]> r = new ArrayList<Object[]>();
            Cursor cur = null;

            try {
                // 从notes表中查出所有使用该笔记类型的笔记来；
                cur = mCol.getDb().getDatabase()
                        .rawQuery("select id, flds from notes where mid = " + m.getLong("id"), null);
                while (cur.moveToNext()) {
                    r.add(new Object[] {
                            // fn.transform(Utils.splitFields(cur.getString(1)) 返回一个新的字段数组；
                            Utils.joinFields((String[]) fn.transform(Utils.splitFields(cur.getString(1)))),
                            Utils.intNow(), mCol.usn(), cur.getLong(0) });
                }
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
            mCol.getDb().executeMany("update notes set flds=?,mod=?,usn=? where id = ?", r);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Templates ***********************************************************************************************
     * 创建新的卡片模板；
     */

    public JSONObject newTemplate(String name) {
        JSONObject t;
        try {
            t = new JSONObject(defaultTemplate);
            t.put("name", name);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return t;
    }


    /** Note: should col.genCards() afterwards.
     * 增加新的卡片模板
     * @throws ConfirmModSchemaException */
    public void addTemplate(JSONObject m, JSONObject template) throws ConfirmModSchemaException {
        try {
            if (m.getLong("id") != 0) {
                mCol.modSchema(true);
            }
            JSONArray ja = m.getJSONArray("tmpls");
            ja.put(template);
            m.put("tmpls", ja);
            // 跟新卡片模板的索引号
            _updateTemplOrds(m);
            save(m);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Removing a template
     * 删除一个卡片模板
     * @return False if removing template would leave orphan notes.删除卡片模板将留下孤零零的笔记；
     * @throws ConfirmModSchemaException 
     */
    public boolean remTemplate(JSONObject m, JSONObject template) throws ConfirmModSchemaException {
        try {
            // Todo_john 假定卡片模板的数量大于1，
            assert (m.getJSONArray("tmpls").length() > 1);
            // find cards using this template
            // 找到传入的卡片模板，在笔记类型中的卡片模板中的索引号；
            JSONArray ja = m.getJSONArray("tmpls");
            int ord = -1;
            for (int i = 0; i < ja.length(); ++i) {
                if (ja.get(i).equals(template)) {
                    ord = i;
                    break;
                }
            }
            // 找出所有使用此卡片模板的卡片；
            String sql = "select c.id from cards c, notes f where c.nid=f.id and mid = " +
                    m.getLong("id") + " and ord = " + ord;
            long[] cids = Utils.toPrimitive(mCol.getDb().queryColumn(Long.class, sql, 0));
            // all notes with this template must have at least two cards, or we could end up creating orphaned notes
            /**(select nid from cards where id in " +Utils.ids2str(cids) + ")意义在于，从这些卡片中再找到卡片使用到的nid，
             * select nid, count() from cards where nid in(...)意在将寻找到的nid,合并，取出其使用次数；
             * group by nid having count() < 2 limit 1 根据nid排序，筛选出count()小于2的，并且只要第一个记录；
             */
            sql = "select nid, count() from cards where nid in (select nid from cards where id in " +
                    Utils.ids2str(cids) + ") group by nid having count() < 2 limit 1";
            if (mCol.getDb().queryScalar(sql) != 0) {
                //如果按照这条查询语句查到了记录，说明，存在这样的笔记，即这个笔记只有一张卡片，并且这张卡片恰好是用的当前卡片模板；如果移除这个卡片模板，即是删除此卡片，笔记将变得孤零零；
                return false;
            }
            // ok to proceed; remove cards
            // 如果走到这一步，说明ok了，现在可以移除这个卡片模板
            // 首先做个schema改变标记，用于强制同步；
            mCol.modSchema(true);
            // 删除这些相关卡片
            mCol.remCards(cids);
            // shift ordinals移动序号，对于卡片的卡片模板索引号ord大于传入的模板的索引号的卡片，需要跟新数据库信息；
            mCol.getDb()
                    .execute(
                            "update cards set ord = ord - 1, usn = ?, mod = ? where nid in (select id from notes where mid = ?) and ord > ?",
                            new Object[] { mCol.usn(), Utils.intNow(), m.getLong("id"), ord });
            JSONArray tmpls = m.getJSONArray("tmpls");
            // 创建新的json数组ja2,用于承载新的卡片模板，
            JSONArray ja2 = new JSONArray();
            for (int i = 0; i < tmpls.length(); ++i) {
                if (template.equals(tmpls.getJSONObject(i))) {
                    continue;
                }
                ja2.put(tmpls.get(i));
            }
            m.put("tmpls", ja2);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        // 更新卡片模板索引号，
        _updateTemplOrds(m);
        save(m);
        return true;
    }

    // 更新卡片模板的索引号；
    public void _updateTemplOrds(JSONObject m) {
        JSONArray ja;
        try {
            ja = m.getJSONArray("tmpls");
            for (int i = 0; i < ja.length(); i++) {
                JSONObject f = ja.getJSONObject(i);
                f.put("ord", i);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 移动卡片模板，即改变卡片模板的索引号，
    public void moveTemplate(JSONObject m, JSONObject template, int idx) {
        try {
            JSONArray ja = m.getJSONArray("tmpls");
            int oldidx = -1;
            // 创建一个新的卡片模板json集合l;
            ArrayList<JSONObject> l = new ArrayList<JSONObject>();
            // oldidxs存放旧的模板和其对应的索引号；
            HashMap<Integer, Integer> oldidxs = new HashMap<Integer, Integer>();
            for (int i = 0; i < ja.length(); ++i) {
                if (ja.get(i).equals(template)) {
                    oldidx = i;
                    if (idx == oldidx) {
                        return;
                    }
                }
                JSONObject t = ja.getJSONObject(i);
                oldidxs.put(t.hashCode(), t.getInt("ord"));
                l.add(t);
            }
            l.remove(oldidx);
            l.add(idx, template);
            m.put("tmpls", new JSONArray(l));
            _updateTemplOrds(m);
            // generate change map - We use StringBuilder
            StringBuilder sb = new StringBuilder();
            ja = m.getJSONArray("tmpls");
            /**
             * 拼接sb字符串，如果有五个卡片模板，我们现在要将第4个移动到1，怎，拼接的字符串就是：
             *   when ord = 0 then 0 "" when ord = 3 then 1 "" when ord = 1 then 2"" when ord = 2 then 3"" when ord = 4 then 4
             */
            for (int i = 0; i < ja.length(); ++i) {
                JSONObject t = ja.getJSONObject(i);
                sb.append("when ord = ").append(oldidxs.get(t.hashCode())).append(" then ").append(t.getInt("ord"));
                if (i != ja.length() - 1) {
                    sb.append(" ");
                }
            }
            // apply
            save(m);
            // 更新cards表中的ord,以及相关字段；
            // 使用sql语句的case when end 语法；
            mCol.getDb().execute("update cards set ord = (case " + sb.toString() +
                            " end),usn=?,mod=? where nid in (select id from notes where mid = ?)",
                    new Object[]{mCol.usn(), Utils.intNow(), m.getLong("id")});
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 同步卡片模板；为用到这个笔记类型的笔记重新生成卡片
    private void _syncTemplates(JSONObject m) {
        // nids(m) 即为，用到此笔记类型的所有笔记
        ArrayList<Long> rem = mCol.genCards(Utils.arrayList2array(nids(m)));
    }


    /**
     * Model changing ***********************************************************************************************
     */

    /**
     * Change a model改变一个笔记类型的模板
     * @param m The model to change.要改变的那个笔记类型
     * @param nids The list of notes that the change applies to. 要改变的笔记类型将会影响到的笔记
     * @param newModel For replacing the old model with another one. Should be self if the model is not changing 新的笔记类型
     * @param fmap Map for switching fields. This is ord->ord and there should not be duplicate targets 字段的集合，用于字段的切换
     * @param cmap Map for switching cards. This is ord->ord and there should not be duplicate targets  卡片的集合，用于切换卡片；
     * @throws ConfirmModSchemaException 
     */
    public void change(JSONObject m, long[] nids, JSONObject newModel, Map<Integer, Integer> fmap, Map<Integer, Integer> cmap) throws ConfirmModSchemaException {
        mCol.modSchema(true);
        try {
            // 假定新的笔记类型的id，等于原来的笔记类型的id，或者
            assert (newModel.getLong("id") == m.getLong("id")) || (fmap != null && cmap != null);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        if (fmap != null) {
            _changeNotes(nids, newModel, fmap);
        }
        if (cmap != null) {
            _changeCards(nids, m, newModel, cmap);
        }
        mCol.genCards(nids);
    }

    /**
     *  改变笔记，_changeNotes(nids, newModel, fmap);
     * @param nids 新的卡片模板将会影响到的笔记；
     * @param newModel 新的卡片模板
     * @param map 字段的切换，指，笔记类型中的字段调换了索引位置，而产生的集合字典；
     */
    private void _changeNotes(long[] nids, JSONObject newModel, Map<Integer, Integer> map) {
        List<Object[]> d = new ArrayList<Object[]>();
        int nfields;
        long mid;
        try {
            // nfields 指新的卡片类型包含的字段的个数
            nfields = newModel.getJSONArray("flds").length();
            // mid 新的卡片类型的id
            mid = newModel.getLong("id");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        Cursor cur = null;
        try {
            // concat是链接字符串的一个方法，此代码意为在notes表中查询到形参中涉及的笔记id的记录，
            cur = mCol.getDb().getDatabase().rawQuery(
                    "select id, flds from notes where id in ".concat(Utils.ids2str(nids)), null);
            while (cur.moveToNext()) {
                long nid = cur.getLong(0);
                String[] flds = Utils.splitFields(cur.getString(1));
                Map<Integer, String> newflds = new HashMap<Integer, String>();

                // 根据map调换字段的索引位置，
                for (Integer old : map.keySet()) {
                    newflds.put(map.get(old), flds[old]);
                }
                // 调整完位置后，重新排队，生成新的字段集合
                List<String> flds2 = new ArrayList<String>();
                for (int c = 0; c < nfields; ++c) {
                    if (newflds.containsKey(c)) {
                        flds2.add(newflds.get(c));
                    } else {
                        flds2.add("");
                    }
                }
                // 将新的字段集合变成字符串，并更新写入笔记的表中，
                String joinedFlds = Utils.joinFields(flds2.toArray(new String[flds2.size()]));
                d.add(new Object[] { joinedFlds, mid, Utils.intNow(), mCol.usn(), nid });
            }
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("update notes set flds=?,mid=?,mod=?,usn=? where id = ?", d);
        // updateFieldCache方法的作用: 更新数据库笔记表中的内容，特别是更新排序字段，和csum字段，此字段由flds第一个值得哈希值而来，可以避免笔记重复；
        mCol.updateFieldCache(nids);
    }

    /**
     *
     * @param nids
     * @param oldModel
     * @param newModel
     * @param map
     */
    private void _changeCards(long[] nids, JSONObject oldModel, JSONObject newModel, Map<Integer, Integer> map) {
        List<Object[]> d = new ArrayList<Object[]>();
        List<Long> deleted = new ArrayList<Long>();
        Cursor cur = null;
        int omType;
        int nmType;
        int nflds;
        try {
            omType = oldModel.getInt("type");
            nmType = newModel.getInt("type");
            nflds = newModel.getJSONArray("tmpls").length();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        try {
            cur = mCol.getDb().getDatabase().rawQuery(
                    "select id, ord from cards where nid in ".concat(Utils.ids2str(nids)), null);
            while (cur.moveToNext()) {
                // if the src model is a cloze, we ignore the map, as the gui doesn't currently
                // support mapping them
                Integer newOrd;
                long cid = cur.getLong(0);
                int ord = cur.getInt(1);
                if (omType == Consts.MODEL_CLOZE) {
                    newOrd = cur.getInt(1);
                    if (nmType != Consts.MODEL_CLOZE) {
                        // if we're mapping to a regular note, we need to check if
                        // the destination ord is valid
                        if (nflds <= ord) {
                            newOrd = null;
                        }
                    }
                } else {
                    // mapping from a regular note, so the map should be valid
                    newOrd = map.get(ord);
                }
                if (newOrd != null) {
                    d.add(new Object[] { newOrd, mCol.usn(), Utils.intNow(), cid });
                } else {
                    deleted.add(cid);
                }
            }
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("update cards set ord=?,usn=?,mod=? where id=?", d);
        mCol.remCards(Utils.toPrimitive(deleted));
    }

    /**
     * Schema hash ***********************************************************************************************
     */

    /** Return a hash of the schema, to see if models are compatible.
     * 返回一个schema的哈希值，用来判断此笔记类型是否兼容；
     * */
    public String scmhash(JSONObject m) {
        String s = "";
        try {
        	JSONArray flds = m.getJSONArray("flds");
            for (int i = 0; i < flds.length(); ++i) {
                s += flds.getJSONObject(i).getString("name");
            }
            JSONArray tmpls = m.getJSONArray("tmpls");
            for (int i = 0; i < tmpls.length(); ++i) {
            	JSONObject t = tmpls.getJSONObject(i);
                s += t.getString("name");
                s += t.getString("qfmt");
                s += t.getString("afmt");
           }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return Utils.checksum(s);
    }


    /**
     * Required field/text cache
     * 更新笔记类型中的req字段，它描述着每个卡片模板的question所需要的字段信息；
     * ***********************************************************************************************
     */

    private void _updateRequired(JSONObject m) {
        try {
            // 如果是填空题类型，则什么都不做；
            if (m.getInt("type") == Consts.MODEL_CLOZE) {
                // nothing to do
                return;
            }
            JSONArray req = new JSONArray();
            // 创建一个字符串集合，flds,它将保存着该笔记类型的所有字段名称；
            ArrayList<String> flds = new ArrayList<String>();
            JSONArray fields;
            fields = m.getJSONArray("flds");
            for (int i = 0; i < fields.length(); i++) {
                flds.add(fields.getJSONObject(i).getString("name"));
            }
            // 获取形参 笔记类型的 卡片模板字段；
            JSONArray templates = m.getJSONArray("tmpls");
            for (int i = 0; i < templates.length(); i++) {
                JSONObject t = templates.getJSONObject(i);
                // 获取这个卡片模板的正面，即question所需要的字段
                Object[] ret = _reqForTemplate(m, flds, t);
                JSONArray r = new JSONArray();
                r.put(t.getInt("ord"));
                r.put(ret[0]);
                r.put(ret[1]);
                // 向笔记模型的 req字段添加每个卡片模板对应的内容；
                req.put(r);
            }
            m.put("req", req);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param m 是Model ,是笔记类型；
     * @param flds  是笔记类型中的字段名称的集合；
     * @param t 是笔记类型中的 卡片模板中的某一个；
     * @return 这个卡片模板中需要的字段；比如：{“all”, [1]},或是{“any”,[0,1,3]}
     */
    private Object[] _reqForTemplate(JSONObject m, ArrayList<String> flds, JSONObject t) {
        try {
            ArrayList<String> a = new ArrayList<String>();
            ArrayList<String> b = new ArrayList<String>();
            // flds 是一个字符串集合，它保存着当前笔记类型的所有字段名称；
            // for循环 结束，a , b 都有与flds一样多的元素，
            for (String f : flds) {
                a.add("ankiflag");
                b.add("");
            }
            Object[] data;
            // a.toArray(new String[a.size()]) 将a这个字符串集合变成数组，
            // 则Utils.joinFields(a.toArray(new String[a.size()]))的结果
            // 将是"ankiflag\u001fankiflag\u001f", 它将作为每个字段的实际值生成的字符串flds，然后被传出去；
            // data is [cid, nid, mid, did, ord, tags, flds]
            data = new Object[] { 1l, 1l, m.getLong("id"), 1l, t.getInt("ord"), "",
                    Utils.joinFields(a.toArray(new String[a.size()])) };
            // _renderQA(data)返回保存三个元素的字典：具体内容：
            // "q"->"ankiflag"
            // "a"->"ankiflag\n\n<hr id=answer>\n\nankiflag"
            // "id"->"1l"
            String full = mCol._renderQA(data).get("q");
            data = new Object[] { 1l, 1l, m.getLong("id"), 1l, t.getInt("ord"), "",
                    Utils.joinFields(b.toArray(new String[b.size()])) };
            String empty = mCol._renderQA(data).get("q");
            // if full and empty are the same, the template is invalid and there is no way to satisfy it
            // 如果full 和 empty 是相同的，即，当前卡片模板中的问题没有用到任何字段，则卡片模板无效，没有办法去满足它
            if (full.equals(empty)) {
                return new Object[] { "none", new JSONArray(), new JSONArray() };
            }
            // 处理all类型的：
            String type = "all";
            JSONArray req = new JSONArray();
            ArrayList<String> tmp = new ArrayList<String>();
            for (int i = 0; i < flds.size(); i++) {
                tmp.clear();
                tmp.addAll(a);
                tmp.set(i, "");
                data[6] = Utils.joinFields(tmp.toArray(new String[tmp.size()]));
                // if no field content appeared, field is required
                if (!mCol._renderQA(data).get("q").contains("ankiflag")) {
                    //如果不包含ankiflag,说明，则其他字段都没有参与到question中，则，这个时候当前字段必须参与到question中；
                    //即，all的情况下，只有一个字段参与到question中，
                    req.put(i);
                }
            }
            if (req.length() > 0) {
                // 这种情况，问题question中需要一个字段，我们返回{"all", req}
                return new Object[] { type, req };
            }
            // if there are no required fields, switch to any mode
            type = "any";
            req = new JSONArray();
            for (int i = 0; i < flds.size(); i++) {
                tmp.clear();
                tmp.addAll(b);
                tmp.set(i, "1");
                data[6] = Utils.joinFields(tmp.toArray(new String[tmp.size()]));
                // if not the same as empty, this field can make the card non-blank
                // 即，如果返回的question不为空，则说明，question中包含这个字段，
                if (!mCol._renderQA(data).get("q").equals(empty)) {
                    req.put(i);
                }
            }
            return new Object[] { type, req };
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /** Given a joined field string, return available template ordinals
     * 根据传入的笔记类型和字段值，来判断，那些卡片模板是可用的，并将其卡片模板的序列号封装进一个集合将其返回；
     * */
    public ArrayList<Integer> availOrds(JSONObject m, String flds) {
        try {
            if (m.getInt("type") == Consts.MODEL_CLOZE) {
                return _availClozeOrds(m, flds);
            }
            String[] fields = Utils.splitFields(flds);
            for (String f : fields) {
                f = f.trim();
            }
            ArrayList<Integer> avail = new ArrayList<Integer>();
            JSONArray reqArray = m.getJSONArray("req");
            for (int i = 0; i < reqArray.length(); i++) {
                JSONArray sr = reqArray.getJSONArray(i);

                int ord = sr.getInt(0);
                String type = sr.getString(1);
                JSONArray req = sr.getJSONArray(2);

                if (type.equals("none")) {
                    // unsatisfiable template
                    continue;
                } else if (type.equals("all")) {
                    // AND requirement?
                    boolean ok = true;
                    for (int j = 0; j < req.length(); j++) {
                        int idx = req.getInt(j);
                        if (fields[idx] == null || fields[idx].length() == 0) {
                            // missing and was required
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                } else if (type.equals("any")) {
                    // OR requirement?
                    boolean ok = false;
                    for (int j = 0; j < req.length(); j++) {
                        int idx = req.getInt(j);
                        if (fields[idx] != null && fields[idx].length() != 0) {
                            // missing and was required
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        continue;
                    }
                }
                avail.add(ord);
            }
            return avail;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    public ArrayList<Integer> _availClozeOrds(JSONObject m, String flds) {
        return _availClozeOrds(m, flds, true);
    }

    /**
     * 此方法的作用是 返回一个可用的填空题的序列号集合；
     * @param m 笔记类型；
     * @param flds 字段值的数组，存放实实在在的字段值，而不是字段名；
     * @param allowEmpty 是否允许字段中部挖空内容，不挖空就不是填空题了，
     * @return
     */
    public ArrayList<Integer> _availClozeOrds(JSONObject m, String flds, boolean allowEmpty) {
        // 获取字段数组
        String[] sflds = Utils.splitFields(flds);
        /** "Mapping of field name -> (ord, field).
         *  比如： 字段有“正面”和“背面”，正面是第一个字段，背面是第二个字段；
         *  则返回的map集合的第一个元素就是下面的样式：
         *  { “文字” {0， {name:“文字”， sticky:false, rtl:false, ord:0, font:"Arial", size:20}}}
         *  { “额外的” {1， {name:“额外的”， sticky:false, rtl:false, ord:1, font:"Arial", size:20}}}
         * */
        Map<String, Pair<Integer, JSONObject>> map = fieldMap(m);
        Set<Integer> ords = new HashSet<Integer>();
        List<String> matches = new ArrayList<String>();
        Matcher mm;
        try {
            // m.getJSONArray("tmpls").getJSONObject(0).getString("qfmt")的结果是 “{{cloze:文字}}”
            mm = fClozePattern1.matcher(m.getJSONArray("tmpls").getJSONObject(0).getString("qfmt"));
            while (mm.find()) {
                // faa{{cloze:文字}}fdas---则，group(1)的结果是 文字
                matches.add(mm.group(1));
            }
            mm = fClozePattern2.matcher(m.getJSONArray("tmpls").getJSONObject(0).getString("qfmt"));
            while (mm.find()) {
                // <%cloze:文字%>---筛选出 “文字”将其加入到集合matches中；
                matches.add(mm.group(1));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        for (String fname : matches) {
            /**
            *  则返回的map集合的第一个元素就是下面的样式：
            *  { “文字” {0， {name:“文字”， sticky:false, rtl:false, ord:0, font:"Arial", size:20}}}
            *  { “额外的” {1， {name:“额外的”， sticky:false, rtl:false, ord:1, font:"Arial", size:20}}}
             *  */
            if (!map.containsKey(fname)) {
                continue;
            }
            int ord = map.get(fname).first;
            // sflds 是字段数组；例如：“中国的首都的{{c1::东边}}是天津市；”
            mm = fClozeOrdPattern.matcher(sflds[ord]);
            while (mm.find()) {
                // {{c1::东边}}---则mm.group(1)结果是1
                ords.add(Integer.parseInt(mm.group(1)) - 1);
            }
            // 如果这个字段挖了三个空，则ords的内容就是{0， 1， 2}，以此类推；
        }
        if (ords.contains(-1)) {
            ords.remove(-1);
        }
        if (ords.isEmpty() && allowEmpty) {
            // 如果没有挖空，并且允许这样不挖空，则返回一个只有{0}的集合；
            return new ArrayList<Integer>(Arrays.asList(new Integer[] { 0 }));
        }
        return new ArrayList<Integer>(ords);
    }


    /**
     * Sync handling ***********************************************************************************************
     * 上传之前的处理，即同步前处理；
     */

    public void beforeUpload() {
        try {
            // all() 获取当前牌组集合中的所有笔记类型；将usn，唯一序列号设置为0；
            for (JSONObject m : all()) {
                m.put("usn", 0);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        save();
    }


    /**
     * Routines from Stdmodels.py
     * *添加基本笔记类型；
     * @throws ConfirmModSchemaException **********************************************************************************************
     */

    public static JSONObject addBasicModel(Collection col) throws ConfirmModSchemaException {
        return addBasicModel(col, "Basic");
    }


    /**
     * 添加一个新的基本笔记类型；名字叫做name
     * @param col
     * @param name
     * @return
     * @throws ConfirmModSchemaException
     */
    public static JSONObject addBasicModel(Collection col, String name) throws ConfirmModSchemaException {
        // 获取Models对象；
        Models mm = col.getModels();
        // 创建一个新的笔记类型名字叫name;
        JSONObject m = mm.newModel(name);
        // 创建一个新的字段名字叫Front
        JSONObject fm = mm.newField("Front");
        // 添加这个新的字段；
        mm.addField(m, fm);
        fm = mm.newField("Back");
        mm.addField(m, fm);
        // 添加一个新的卡片模板；
        JSONObject t = mm.newTemplate("Card 1");
        try {
            t.put("qfmt", "{{Front}}");
            t.put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        mm.addTemplate(m, t);
        mm.add(m);
        return m;
    }

    /** Forward & Reverse
     * 添加反转功能；
     * */

    public static JSONObject addForwardReverse(Collection col) throws ConfirmModSchemaException {
    	String name = "Basic (and reversed card)";
        Models mm = col.getModels();
        JSONObject m = addBasicModel(col);
        try {
            m.put("name", name);
            JSONObject t = mm.newTemplate("Card 2");
            t.put("qfmt", "{{Back}}");
            t.put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Front}}");
            mm.addTemplate(m, t);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return m;
    }


    /** Forward & Optional Reverse
     * 向前和可选反转；
     * */

    public static JSONObject addForwardOptionalReverse(Collection col) throws ConfirmModSchemaException {
    	String name = "Basic (optional reversed card)";
        Models mm = col.getModels();
        JSONObject m = addBasicModel(col);
        try {
            m.put("name", name);
            JSONObject fm = mm.newField("Add Reverse");
            mm.addField(m, fm);
            JSONObject t = mm.newTemplate("Card 2");
            t.put("qfmt", "{{#Add Reverse}}{{Back}}{{/Add Reverse}}");
            t.put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Front}}");
            mm.addTemplate(m, t);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    /**
     * 添加填空题类型的笔记类型；
     * @param col
     * @return
     * @throws ConfirmModSchemaException
     */
    public static JSONObject addClozeModel(Collection col) throws ConfirmModSchemaException {
        Models mm = col.getModels();
        JSONObject m = mm.newModel("Cloze");
        try {
            m.put("type", Consts.MODEL_CLOZE);
            String txt = "Text";
            JSONObject fm = mm.newField(txt);
            mm.addField(m, fm);
            fm = mm.newField("Extra");
            mm.addField(m, fm);
            JSONObject t = mm.newTemplate("Cloze");
            String fmt = "{{cloze:" + txt + "}}";
            m.put("css", m.getString("css") + ".cloze {" + "font-weight: bold;" + "color: blue;" + "}");
            t.put("qfmt", fmt);
            t.put("afmt", fmt + "<br>\n{{Extra}}");
            mm.addTemplate(m, t);
            mm.add(m);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return m;
    }


    /**
     * Other stuff NOT IN LIBANKI
     * ***********************************************************************************************
     */

    public void setChanged() {
        mChanged = true;
    }


    /*返回牌组集合中所有的笔记类型和每个笔记类型对应的卡片模型；*/
    public HashMap<Long, HashMap<Integer, String>> getTemplateNames() {
        HashMap<Long, HashMap<Integer, String>> result = new HashMap<Long, HashMap<Integer, String>>();
        for (JSONObject m : mModels.values()) {
            JSONArray templates;
            try {
                templates = m.getJSONArray("tmpls");
                HashMap<Integer, String> names = new HashMap<Integer, String>();
                for (int i = 0; i < templates.length(); i++) {
                    JSONObject t = templates.getJSONObject(i);
                    names.put(t.getInt("ord"), t.getString("name"));
                }
                result.put(m.getLong("id"), names);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }


    /**
     * @return the ID
     */
    public int getId() {
        return mId;
    }


    /**
     * @return the name
     * 返回笔记类型的名字
     */
    public String getName() {
        return mName;
    }

    // 放回所有的笔记类型；
    public HashMap<Long, JSONObject> getModels() {
        return mModels;
    }

    /** Validate model entries.
     *  检查笔记类型的有效性；
     * */
	public boolean validateModel() {
		Iterator<Entry<Long, JSONObject>> iterator = mModels.entrySet().iterator();
		while (iterator.hasNext()) {
			if (!validateBrackets(iterator.next().getValue())) {
				return false;
			}
		}
		return true;
	}

	/** Check if there is a right bracket for every left bracket.
     * 检查是否每一个左括号都有一个右括号与其匹配
     * */
	private boolean validateBrackets(JSONObject value) {
		String s = value.toString();
		int count = 0;
		boolean inQuotes = false;
		char[] ar = s.toCharArray();
		for (int i = 0; i < ar.length; i++) {
			char c = ar[i];
			// if in quotes, do not count
			if (c == '"' && (i == 0 || (ar[i-1] != '\\'))) {
				inQuotes = !inQuotes;
				continue;
			}
			if (inQuotes) {
				continue;
			}
			switch(c) {
			case '{':
				count++;
				break;
			case '}':
				count--;
				if (count < 0) {
					return false;
				}
				break;
			}
		}
		return (count == 0);
	}
}
