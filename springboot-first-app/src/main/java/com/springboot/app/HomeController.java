package com.springboot.app;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;
import org.jsoup.select.Evaluator.ContainsText;
import org.jsoup.select.Selector;
//import org.springframework.data.mongodb.core.mapping.Document;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import ca.rmen.porterstemmer.PorterStemmer;

@RestController
public class HomeController {

	static HashMap<String,ArrayList<Document>> ReterivedDocuments;
	//static HashMap.Entry<String,ArrayList<Document>> entry;
	static ArrayList<Document> docs;
	private static ArrayList<Double> tf_idf = new ArrayList<Double>();
	private static HashMap<String,Double>out = new HashMap<String,Double>();
	private static ArrayList<String> urls = new ArrayList<String>(); ;
	private static int TotalPages = 1000;
	private static int no_urls;
	private static ArrayList<String> Copy_String_list = new ArrayList<String>();
	private static String last_query = new String();
	private static boolean is_phrase = false;
	//public static int number_documents;
	@RequestMapping(value="/mainpage/")
	public String mainpage()
	{
		return  "index.html";
	}
	public static void clear()
	{
		Copy_String_list.clear();
		urls.clear();
		no_urls = 0;
		tf_idf.clear();
		out.clear();
		is_phrase = false;
	}

	// suggestion backend for interface--------------------------------------------------------------------
	@GetMapping(value="/suggestion/{query}")
	public ArrayList<String> test(@PathVariable String query)
	{	
		ArrayList<String> suggestion_array = new ArrayList<String>();
		if(query =="")
		{
			return suggestion_array;
		}
		MongoDatabase SUGGESTION_DB = Indexer.get_database("SUGGESTION",Indexer.crawler_database_connection);
		MongoCollection<Document> SUGGESTION_COL = Indexer.get_collection(SUGGESTION_DB, "SUGGESTION_COL");
		
		// search for query using substring to return previous submitted queries
		Pattern pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
		Bson filter = Filters.regex("_id", pattern);
		FindIterable<Document> iterDoc = SUGGESTION_COL.find(filter).sort(Sorts.descending("count"));
		MongoCursor<Document> it = iterDoc.iterator();
		int counter =0;
		while(it.hasNext())
		{
			counter++;
			Document suggestion_query = it.next();
			String temp_query = suggestion_query.getString("_id"); 
			suggestion_array.add(temp_query);
			System.out.println("Query "+counter+" : "+temp_query);
			System.out.println("Suggestion Queries Found "+counter);
		}
		return suggestion_array;
	}
	
	// Query Processor  -----------------------------------------------------------------------------------
	@GetMapping(value="/data/{query}")
	public ArrayList<Document> DatabaseResponse(@PathVariable String query)
	{
		
		// check if query is submitted before or not 
		clear();
		if(query.startsWith("\"") && query.endsWith("\"") && query.length()>2)
		{
			System.out.println(query);
			is_phrase = true;
		}
		
		//last_query = query;
		MongoDatabase SUGGESTION_DB = Indexer.get_database("SUGGESTION",Indexer.crawler_database_connection);
		MongoCollection<Document> SUGGESTION_COL = Indexer.get_collection(SUGGESTION_DB, "SUGGESTION_COL");
		FindIterable<Document> iterDoc_SUGGESTION = SUGGESTION_COL.find(Filters.eq("_id",query));
		MongoCursor<Document> it_SUGGESTION = iterDoc_SUGGESTION.iterator();
		if(it_SUGGESTION.hasNext())
		{
			int count_query = 0;
			Document doc = it_SUGGESTION.next();
			try
			{
				count_query = doc.getInteger("count");
				count_query++;
			}
			catch(Exception e)
			{
				System.out.println("Count not found in suggestion");
				count_query = 1;
			}
			SUGGESTION_COL.updateOne(Filters.eq("_id",doc.getString("_id")), Updates.set("count",count_query ));
		}
		else
		{
			try
			{
				Document doc=  new Document("_id",query);
				doc.append("count", 0);
				SUGGESTION_COL.insertOne(doc);
			}
			catch(Exception e)
			{
				System.out.println("Query already in database");
			}	
		}
		// retreive query from database----------------------------------------------
		ArrayList<Document> JSON_Data = new ArrayList<Document>();
		//query = query.toLowerCase();
		if(is_phrase)
		{
			query = query.substring(0, query.length() - 1);
			query = query.substring(1);
		}
		last_query = query;
		String Copy_query = new String (query);;
		copy(Copy_query);
		ReterivedDocuments = Query_Process (query);
		System.out.println("urls arr size reterived from database = "+no_urls);
		if(ReterivedDocuments == null)
		{
			JSON_Data.add(new Document("SIZE",no_urls));
			return JSON_Data;
		}
		else
		{
			System.out.println("number of documents retrived from database = "+ReterivedDocuments.size());
		}
			
		// call phrase search----------------------------------------------------------------------
		if (is_phrase && Copy_String_list.size()>1)
		{
			
			long start_phrase = System.currentTimeMillis();
			urls = phraseSearch(ReterivedDocuments);
			System.out.println("Phrase search: no of phrase urls = "+urls.size());
			long end_phrase = System.currentTimeMillis();
			System.out.println("Phrase search: Time of phrase = "+(end_phrase-start_phrase));
			// rank output of urls reterived from phrase search
			rank_phrase__search_documents();
		}
		// call ranker ----------------------------------------------------------------------------
		long start_ranker = System.currentTimeMillis();
		urls = TF_IDF();
		long end_ranker = System.currentTimeMillis();
		System.out.println("ranker: Time of ranker = "+(end_ranker-start_ranker));
		System.out.println("ranker: no of ranker urls = "+urls.size());

		

		// merge urls--------------------------------------------------------------------------------
//		HashMap<String, Integer> temphash = new HashMap<String,Integer>();
//		for(int i=0;i<phrase_urls.size();i++)
//		{
//			urls.add(phrase_urls.get(i));
//			temphash.put(phrase_urls.get(i), 1);
//		}
//		for(int i=0;i<ranker_urls.size();i++)
//		{
//			if(temphash.get(ranker_urls.get(i))  == null || temphash.get(ranker_urls.get(i))  == 0)
//			{
//				urls.add(ranker_urls.get(i));
//				temphash.put(ranker_urls.get(i), 1);
//			}
//		}
		
		// get  title and description for each document ------ get from database or parse-------
		no_urls = urls.size();
		System.out.println("Number of urls of document send to front = "+no_urls);
		JSON_Data.add(new Document("SIZE",no_urls)); // send first document size to front
		int count_urls =0;
		if(no_urls>10)
		{
			count_urls =10;
		}
		else
		{
			count_urls =no_urls;
		}
		long start = System.currentTimeMillis();
		for(int i =0;i<count_urls;i++)
		{
			long start_1 = System.currentTimeMillis();
			// get data of url--------------------------------------------------------------------
			String html_string = get_html(urls.get(i));
			html_string = html_string.trim().replaceAll("\\s{5,}", "     ");
			org.jsoup.nodes.Document htmldoc = Jsoup.parse(html_string);
			//get title --------------------------------------------------------------------------
			Elements temp_TITLE= htmldoc.select("meta");
			temp_TITLE = temp_TITLE.attr("name","description");
			//String TITLE = temp_TITLE.attr("content").;
			String TITLE =htmldoc.select("title").toString();
			boolean found_title = false;
			for (Element link : temp_TITLE) {
				
					String Tempstr= link.attr("content");
					//Tempstr = Tempstr.toLowerCase();
					if(Tempstr.toLowerCase().contains(query.toLowerCase()))
					{
						TITLE = Tempstr;
						if(TITLE.length()>100)
						{
							TITLE = TITLE.substring(0,100);	
							int lastindex = TITLE.lastIndexOf(" ");
							if(lastindex !=-1)
							{
								TITLE = TITLE.substring(0,lastindex);
								TITLE +="...";
							}
							
						}
						
						System.out.println("Found TITLE as phrase");
						found_title = true;
					}
					else
					{
						for(int k=0;k<Copy_String_list.size();k++)
						{
							if(Tempstr.toLowerCase().contains(Copy_String_list.get(k).toLowerCase()+" "))
							{
								TITLE = Tempstr;
								if(TITLE.length()>100)
								{
									TITLE = TITLE.substring(0,100);	
									int lastindex = TITLE.lastIndexOf(" ");
									if(lastindex !=-1)
									{
										TITLE = TITLE.substring(0,lastindex);
										TITLE +="...";
									}
								}
								found_title = true;
								break;
							}
						}
						if (found_title)
						{
							break;
						}
					}
				  //String linkText = link.text();
				}
			if (!found_title)
			{
				System.out.println("NO TITLE in meta data match query");
			}
			System.out.println("Title = "+TITLE);
			TITLE = Jsoup.clean(TITLE, Whitelist.none()); // remove tags if any found
			// get description ------------------------------------------------------------------
			String description = "no description"; // set value to no description in case of no sentence found
			html_string = Jsoup.clean(html_string, Whitelist.none()); // remove tags first
			//html_string = html_string.toLowerCase();
			description = get_description(html_string,htmldoc);
			if (description =="")
			{
				description = "no description";
			}
			Document json_doc  = new Document("URL",urls.get(i));
			json_doc.append("TITLE",TITLE);
			if(description.length() > 1000)
			{
				json_doc.append("DESCRIPTION",description.substring(0, 999));
			}
			else
			{
				json_doc.append("DESCRIPTION",description);
			}
			
			JSON_Data.add(json_doc);
			long end_1 = System.currentTimeMillis();
			//System.out.println("Time of 1 documents = "+(end_1-start_1));
		}
		long end = System.currentTimeMillis();
		long elapsedTime = end - start;
		System.out.println("Time of "+count_urls+" documents = "+elapsedTime);
		System.out.println(JSON_Data);
		return JSON_Data;
	}
	
	// retreive query from database ----------------------------------------------------------------
		public HashMap<String,ArrayList<Document>> Query_Process (String query)
		{
			
			ArrayList<String> string_array = Indexer.Remove_tags(query);
			string_array = Indexer.Remove_Stop_Words(string_array);
			string_array = Indexer.Stemming(string_array);
			//java.util.logging.Logger.getLogger("org.mongodb.driver").setLevel(Level.OFF);
			MongoDatabase indexerdb = get_database("Search_index", "mongodb+srv://ahmedsabry:searchengine@searchengine.tnuaa.mongodb.net/myFirstDatabase?retryWrites=true&w=majority");
			MongoCollection<Document> indexercol = get_collection(indexerdb, "invertedfile");
			TotalPages = Indexer.countUniqueDoc(indexercol);
			System.out.println("TotalPages = "+TotalPages);
			//HashMap<String,ArrayList<Document>> DocLists = new HashMap<String,ArrayList<Document>>();
			HashMap<String,ArrayList<Document>> DocLists = new LinkedHashMap<String,ArrayList<Document>>();
			for(int i=0;i<string_array.size();i++)
			{
				//System.out.println(string_array.get(i));
				if(!DocLists.containsKey(string_array.get(i)))
				{
					FindIterable<Document> iterDoc = indexercol.find(Filters.eq("Word",string_array.get(i)));
					MongoCursor<Document> it = iterDoc.iterator();
					while(it.hasNext())
					{
						Document doc = it.next();
						if(doc == null)
						{
						}
						if(DocLists.get(string_array.get(i)) == null)
						{
							ArrayList<Document> list = new ArrayList<Document>();
							list.add(doc);
							DocLists.put(string_array.get(i), list);
							//urls.add(doc.getString("DOC_ID"));
						}
						else
						{
							ArrayList<Document> list = DocLists.get(string_array.get(i));
							list.add(doc);
							DocLists.put(string_array.get(i), list);
							//urls.add(doc.getString("DOC_ID"));
						}

					}
					
				}
				else
				{
					
				}
			}
			return DocLists;
		}
		
		// Description Extraction backend interface---------------------------------------------------
	
		public String get_description(String html_string,org.jsoup.nodes.Document htmldoc)
		{
			String description = "";

			if(is_phrase)
			{
				// get phrase quotation-------------------------------------------------------
				description = get_parts(html_string, last_query.toLowerCase(), 300);
				
				System.out.println("Phrase = "+description);
			}
			else
			{	
				// get other descriptions------------------------------------------------------------------
				description = "";
				int lengthofdescription = 200/Copy_String_list.size();
				for(int j =0;j<Copy_String_list.size();j++)
				{
					description += get_parts(html_string, Copy_String_list.get(j).toLowerCase()+" ", lengthofdescription);
					if(description.length() > 500)
					{
						break;
					}					
				}
			}
			System.out.println("Description : "+description);
			return description;
		}


	public String get_parts(String s1,String searchword,int size_before_after)
	{
		String description2 = "";
		int indexof_str = s1.toLowerCase().indexOf(searchword.toLowerCase());
		
		if(indexof_str !=-1)
		{
			//found.
			if(is_phrase)
			{
				s1 = s1.toLowerCase().replaceAll(searchword.toLowerCase()," <strong style=\"color:yellow\">"+searchword+"</strong> ");
				indexof_str = s1.toLowerCase().indexOf(searchword.toLowerCase());
			}
			else
			{
				for(int i=0;i<Copy_String_list.size();i++)
				{
					s1 = s1.toLowerCase().replaceAll(Copy_String_list.get(i).toLowerCase()+" ",
							" <strong style=\"color:yellow\">"+Copy_String_list.get(i).toLowerCase()+"</strong> ");
				}
			}
			int startofsentence = s1.substring(0,indexof_str).lastIndexOf("    ");
			int endofsentence = s1.substring(indexof_str).indexOf("    ");
			System.out.println("start of sentence "+startofsentence);
			System.out.println("start of query "+indexof_str);
			System.out.println("end of sentence "+endofsentence);
			if(startofsentence == -1)
			{
				if(indexof_str-size_before_after >0)
				{
					description2 = s1.substring(indexof_str-size_before_after,indexof_str);
					try {
						description2 = description2.substring(description2.indexOf(" "));
					}
					catch (Exception e)
					{
						
					}
						
				}
				else
				{
					description2 = s1.substring(0,indexof_str);
				}
				
			}
			else
			{
				if(startofsentence < indexof_str-size_before_after)
				{
					
					// need to start from full word
					description2 = s1.substring(indexof_str-size_before_after,indexof_str);
					try {
						description2 = description2.substring(description2.indexOf(" "));
					}
					catch (Exception e)
					{
						
					}
				}
				else
				{
					description2 = s1.substring(startofsentence,indexof_str);
				}
				
			}
			if(endofsentence == -1)
			{
				if(size_before_after+indexof_str < s1.length())
				{
					description2 += s1.substring(indexof_str,indexof_str+size_before_after);
					try {
						description2 = description2.substring(0,description2.lastIndexOf(" "));
					}
					catch (Exception e)
					{
						
					}
				}
				else
				{
					description2 += s1.substring(indexof_str);
				}
				
			}
			else
			{
				if(endofsentence > indexof_str+size_before_after)
				{
					description2 += s1.substring(indexof_str,indexof_str+size_before_after);
					try {
						description2 = description2.substring(0,description2.lastIndexOf(" "));
					}
					catch (Exception e)
					{
						
					}
				}
				else
				{
					description2 += s1.substring(indexof_str,indexof_str+endofsentence);	
				}
			}
			
		}
		if(is_phrase)
		{
			for(int i=0;i<Copy_String_list.size();i++)
			{
				description2 = description2.toLowerCase().replaceAll(Copy_String_list.get(i).toLowerCase()+" ",
						" <strong style=\"color:yellow\">"+Copy_String_list.get(i).toLowerCase()+"</strong> ");
			}
		}
		return description2;
	}
	@GetMapping(value="/page/{page_number}")
	public ArrayList<Document> Pagination(@PathVariable String page_number)
	{
		ArrayList<Document> JSON_Data = new ArrayList<Document>();
		int page_no = Integer.parseInt(page_number);
		int start_loop =0,end_loop =0;
		System.out.println("URLS Size = "+no_urls);
		if(page_no*10 <= no_urls)
		{
			start_loop = (page_no-1)*10;
			end_loop = page_no*10;
		}
	
		else
		{
			start_loop = (page_no-1)*10;
			end_loop = no_urls;
		}
		//String description;
		for(int i=start_loop ;i<end_loop;i++)
		{
			long start_1 = System.currentTimeMillis();
			// get data of url
			String html_string = get_html(urls.get(i));
			html_string = html_string.trim().replaceAll("\\s{5,}", "   ");
			//html_string = html_string.toLowerCase();
			org.jsoup.nodes.Document htmldoc = Jsoup.parse(html_string);
			// get document title
			//get title --------------------------------------------------------------------------
			Elements temp_TITLE= htmldoc.select("meta");
			temp_TITLE = temp_TITLE.attr("name","description");
			//String TITLE = temp_TITLE.attr("content").;
			String TITLE =htmldoc.select("title").toString();;
			boolean found_title = false;
			for (Element link : temp_TITLE) {
				
					String Tempstr= link.attr("content");
					//Tempstr = Tempstr.toLowerCase();
					if(Tempstr.toLowerCase().contains(last_query.toLowerCase()))
					{
						TITLE = Tempstr;
						if(TITLE.length()>100)
						{
							TITLE = TITLE.substring(0,100);	
							int lastindex = TITLE.lastIndexOf(" ");
							if(lastindex !=-1)
							{
								TITLE = TITLE.substring(0,lastindex);
								TITLE +="...";
							}
							
						}
						
						System.out.println("Found TITLE as phrase");
						found_title = true;
					}
					else
					{
						
						for(int k=0;k<Copy_String_list.size();k++)
						{
							if(Tempstr.toLowerCase().contains(Copy_String_list.get(k).toLowerCase()+" "))
							{
								TITLE = Tempstr;
								if(TITLE.length()>100)
								{
									TITLE = TITLE.substring(0,100);	
									int lastindex = TITLE.lastIndexOf(" ");
									if(lastindex !=-1)
									{
										TITLE = TITLE.substring(0,lastindex);
										TITLE +="...";
									}
									
								}
								found_title = true;
								break;
							}
						}
						if (found_title)
						{
							break;
						}
					}
				  //String linkText = link.text();
				}
			if (!found_title)
			{
				System.out.println("NO TITLE in meta data match query");
			}
			System.out.println("Title = "+TITLE);
			TITLE = Jsoup.clean(TITLE, Whitelist.none());

			// get description 
			String description = "no description";
			html_string = Jsoup.clean(html_string, Whitelist.none());
			description = get_description(html_string,htmldoc);
			if (description =="")
			{
				description = "no description";
			}
			Document json_doc  = new Document("URL",urls.get(i));
			json_doc.append("TITLE",TITLE);
			if(description.length() > 1000)
			{
				json_doc.append("DESCRIPTION",description.substring(0, 999));
			}
			else
			{
				json_doc.append("DESCRIPTION",description);
			}
			JSON_Data.add(json_doc);
			long end_1 = System.currentTimeMillis();
			System.out.println("Time of 1 documents = "+(end_1-start_1));
			}
		System.out.println(JSON_Data);
		return JSON_Data;
	}
	

	// copy query to save it 
	public void copy(String query)
	{
		ArrayList<String> string_array = Indexer.Remove_tags(query);
		//System.out.println("no Error sabry");
		string_array = Indexer.Remove_Stop_Words(string_array);
		for(int i=0;i<string_array.size();i++)
		{
			Copy_String_list.add(string_array.get(i));
		}
	}
	public MongoDatabase get_database(String databasename, String connection) {

		 MongoClient mongoClient2 = MongoClients.create("mongodb://localhost:27017");
		 MongoDatabase db = mongoClient2.getDatabase(databasename);
		 return db;
	}

	// function get collection from database
	public  MongoCollection<Document> get_collection(MongoDatabase db, String Collection_Name) {

		MongoCollection<Document> col = db.getCollection(Collection_Name);
		return col;
	}
	
	// get html string stored in database
	public static String get_html(String url)
	{
		MongoDatabase CrawlerDB = Indexer.get_database("Crawler", null);
		MongoCollection<Document> CrawlerCol = Indexer.get_collection(CrawlerDB, "Crawler");
		FindIterable<Document> iterDoc = CrawlerCol.find(Filters.eq("Url",url));
		MongoCursor<Document> it = iterDoc.iterator();
		String html_doc ;
		if(it.hasNext())
		{
			Document doc = it.next();
			html_doc = doc.getString("html");
		}
		else
		{
			System.out.println("No html");
			html_doc = "";
		}
		return html_doc;
		}
	public static void rank_phrase__search_documents()
	{
		HashMap<String, Integer> urls_phrase_hash = new HashMap<String, Integer>();
		System.out.println("rank_phrase__search_documents: "+urls.size());
		for(int i=0;i<urls.size();i++)
		{
			urls_phrase_hash.put(urls.get(i), 1);
		}
		int size_docs_check = 0;
		int size_docs_check_left = 0;

		for (Map.Entry<String,ArrayList<Document>> entry : ReterivedDocuments.entrySet()) 
		{
			ArrayList<Document> arr_docs = entry.getValue();
			ArrayList<Document> new_arr_docs = new ArrayList<Document>();
			for(int j =0;j<arr_docs.size();j++)
			{
				if(urls_phrase_hash.get(arr_docs.get(j).getString("DOC_ID")) != null)
				{
					new_arr_docs.add(arr_docs.get(j));
				}
				else
				{
					size_docs_check_left++;
				}
			}
			ReterivedDocuments.put(entry.getKey(), new_arr_docs);
			size_docs_check += ReterivedDocuments.get(entry.getKey()).size();
   	}
		System.out.println("New Size of Ranker urls = "+size_docs_check);
		
	}
	// Ranker Code ------------------------------------------------------------------------------------------
	
	public static double IDF(int DF)
	{
		return Math.log10((double)TotalPages/DF);
	}
	
	public static ArrayList<String> TF_IDF()
	{	
			//entry = map.entrySet().iterator().next();
		MongoDatabase CrawlerDB = Indexer.get_database("Crawler", null);
		MongoCollection<Document> CrawlerCol = Indexer.get_collection(CrawlerDB, "Crawler");
		for (ArrayList<Document> entry : ReterivedDocuments.values())
		{	
			docs = entry;
			for (Document it : docs)		
			{
				double temp = 0.0;
				//TODO:Calculate the tf of each doc by the formula: tf = 10*title + 5*header + body
				String s = it.getString("DOC_ID");
				Double DbNormTF = it.getDouble("TF");
				FindIterable<Document> iterDoc2 = CrawlerCol.find(Filters.eq("Url", s));
				
				if(DbNormTF < 0.5)
				{
					int DF = it.getInteger("IDF");
					int title = it.getInteger("Title");
					int headers = it.getInteger("Headers");
					int body = it.getInteger("Other");
					int popularity = iterDoc2.first().getInteger("popularity");
//					List<Integer> pos = it.getList("POSITION", Integer.class);
//					int firstPosition = pos.get(0);
					
					int tf = title + headers + body;
					Double totalWords = (tf/DbNormTF);
					
					//TODO:Calculate the normalized tf
					Double ntf = (10*title + 5*headers + body)/totalWords;
					
					//TODO:Calculate TF*IDF
					temp = ntf*IDF(DF) + popularity/1000000.0;
					//temp = ntf*IDF(DF);
				}
				
				Double d = out.get(s);
				if (d != null)
				{
					temp += d;
					out.put(s, temp);
				}
				else
				{
					out.put(s, temp);
				}
				
			}
		}
		// Create a list from elements of HashMap
        List<Map.Entry<String, Double> > list =
               new LinkedList<Map.Entry<String, Double> >(out.entrySet());
 
        // Sort the list
        Collections.sort(list, new Comparator<Map.Entry<String, Double> >() {
            public int compare(Map.Entry<String, Double> o1,
                               Map.Entry<String, Double> o2)
            {
                return (o2.getValue()).compareTo(o1.getValue());
            }
        });

         
        // put data from sorted list to array of strings
        ArrayList<String> finale = new ArrayList<String>();
        for (Map.Entry<String, Double> aa : list) {
            //System.out.println(aa.getKey() + " | " + aa.getValue());
        	finale.add(aa.getKey());
        }
        return finale;
	}
	// phrase search ------------------------------------------------------------------------------------
	public static ArrayList<String> phraseSearch(HashMap<String,ArrayList<Document>>myMap){
		System.out.println("Phrase Searching Started");
		// 34an ana msh 3auzo ye3dl fl trteb
	try {
		HashMap<String , ArrayList<Document>> UrlMap =  new LinkedHashMap<String,ArrayList<Document>>() ; // -> 
		
		Iterator<Entry<String , ArrayList<Document>>> myIterator = myMap.entrySet().iterator() ;
		
		//iterating over the main map 
		while(myIterator.hasNext())
		{
			// accessing the element which the iterator points at
			Map.Entry<String, ArrayList<Document>> newMap = myIterator.next(); 
			
			//iterating over its whole documents and storing it in the new hashmap
			int size = newMap.getValue().size() ; 
			for(int i = 0 ; i < size ; i ++)
			{
				// check if the url exists before in our map 
				var check = UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")); 
				if(check != null) // if yes add this document to it
					UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")).add(newMap.getValue().get(i)); // el document el feha el esm -> ahmed masln
				else // create new place to it and add the document in the List 
				{
					UrlMap.put(newMap.getValue().get(i).getString("DOC_ID"),new ArrayList<Document>()); // create new slot with this URL as a key
					UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")).add(newMap.getValue().get(i)); // add the document in its array
				}
			}			
		}
		
		// to store the positions and work on them 
		// brdu msh 3auz a3dl fl trteb
		HashMap<String , ArrayList<Integer>> Positions =  new LinkedHashMap<String,ArrayList<Integer>>(); 
		
		// iterate over the URLMap
		myIterator = UrlMap.entrySet().iterator(); 
		ArrayList<String> URLContainer = new ArrayList<> (); // hya de el ha7ot feha elly l2eto 
		
		while (myIterator.hasNext())
		{
			Map.Entry<String, ArrayList<Document>> newMap = myIterator.next(); 
			int size = newMap.getValue().size() ; 

			if(size== myMap.size()) // if the URL has number of document = number of elements which we search on it  => doc feha ahmed , w doc feha sabry w doc feha abdelhady
			{
				// iterating over all elements and store their positions
				for(int i = 0 ; i < size ; i ++)
				{
					// ana msh mot2kd mn mwdo3 el casting da bs atmna eno yeshtghl
					Positions.put(newMap.getValue().get(i).getString("Word"), 
							(ArrayList<Integer>)newMap.getValue().get(i).getList("POSITION", // h3dy 3la ahmed ageb el positions bta3tha w a3dy 3la sabry ageb el positions w keda
									Integer.class));					
				}
				
				Iterator<Entry<String , ArrayList<Integer>>> IT = Positions.entrySet().iterator() ;
				Map.Entry<String, ArrayList<Integer>> FirstName = IT.next(); 
				
				// hena b5ly shoghly dayman 3la awl kelma fl searching  w bdwr 3ala el sequance menha baa
				for(int m = 0; m < size ; m++) {
					if(FirstName.getValue().size() > m && compare (size , FirstName.getValue().get(m) , IT, 2)); // el lvlIndc = 2 34an lw el size = 4 ab2a akny shaghal 1 based fa na habd2 mn level 2
						{
							// check for at least 1 lw l2et 
							// add the URl to the founded
							//System.out.println("shit");
							//boolean break_outerloop = false;
							try
							{
								String S1 = get_html(newMap.getKey());
								S1 = Jsoup.clean(S1, Whitelist.none());
								int indexof_str = S1.toLowerCase().indexOf(last_query.toLowerCase());
								if(indexof_str != -1)
								{
									URLContainer.add(newMap.getKey());
									break  ; // e5rog w dwr 3la URL tany ykon feh el sequence da
								} 
								break;
							}
							catch(Exception e)
							{
								System.out.println("Error in get index of query in phrase search");
							}
							//URLContainer.add(newMap.getKey());
							//break  ; // e5rog w dwr 3la URL tany ykon feh el sequence da

						}
				}
			}
		}
		
		return URLContainer; 

		
	}
	catch(Exception e) {
		System.out.println("Phrase Search is returning empty");
		return new ArrayList<String>();
	}
		
	}
	
	public static Boolean compare (int size , Integer number , Iterator<Entry<String , ArrayList<Integer>>> IT, int LvlInd)
	{
		// base case 
		if(LvlInd == size) { // last level 34an ana shghal 1 based
			Map.Entry<String, ArrayList<Integer>> FirstRow = IT.next(); 
			for(int i = 0 ; i < FirstRow.getValue().size(); i++)
			{
				if(FirstRow.getValue().get(i) == number + 1 )
					return true ; // found the last index 
				else if (FirstRow.getValue().get(i) > number ) return false ; 
			}
			return false ;
		}
		// found the sequence 
		Boolean found = false ;
		if(IT.hasNext())
		{
			Map.Entry<String, ArrayList<Integer>> FirstRow = IT.next(); 
			for(int i = 0 ; i < FirstRow.getValue().size(); i++)
			{
				if(FirstRow.getValue().get(i) == number + 1 )
				{
					// ana msh 3arf 7war el hasnext de hya el bt5ly el iterator yet7rk wla a lw hya fa yeb2a keda el shoghl tmaam
					IT.next();
					found = compare(size , number + 1 , IT,LvlInd + 1) ; // found the last index 
				}
				else if (FirstRow.getValue().get(i) > number ) return false ; 
				
				if(found == true) return true;
			}			
		}
		

		
		return found;
	}


}


// old ranker --------------------------------------------------------------------------------------------
//public static double IDF()
//{
//	return Math.log10((double)TotalPages/docs.size());
//}

//public static ArrayList<String> TF_IDF()
//{	
//		//entry = map.entrySet().iterator().next();
//	for (ArrayList<Document> entry : ReterivedDocuments.values())
//	{	
//		docs = entry;
//		for (Document it : docs)		
//		{
//			//TODO:Calculate the tf of each doc by the formula: tf = 10*title + 5*header + body
//			int title = it.getInteger("Title");
//			int headers = it.getInteger("Headers");
//			int body = it.getInteger("Other");
//			String s = it.getString("DOC_ID");
//			
//			int tf = 10*title + 5*headers + body;
//			//TODO:Calculate the normalized tf
//			Double ntf = 1.0*tf;
//			
//			//TODO:Calculate TF*IDF
//			double temp = ntf*IDF();
//			Double d = out.get(s);
//			if (d != null)
//			{
//				temp += d;
//				out.remove(s);
//			}
//			out.put(s, temp);
//		}
//	}
//	// Create a list from elements of HashMap
//    List<Map.Entry<String, Double> > list =
//           new LinkedList<Map.Entry<String, Double> >(out.entrySet());
//
//    // Sort the list
//    Collections.sort(list, new Comparator<Map.Entry<String, Double> >() {
//        public int compare(Map.Entry<String, Double> o1,
//                           Map.Entry<String, Double> o2)
//        {
//            return (o2.getValue()).compareTo(o1.getValue());
//        }
//    });
//     
//    // put data from sorted list to array of strings
//    ArrayList<String> finale = new ArrayList<String>();
//    for (Map.Entry<String, Double> aa : list) {
//        //System.out.println(aa.getKey() + " | " + aa.getValue());
//    	finale.add(aa.getKey());
//    }
//    return finale;
//}
//

// old phrase search -------------------------------------------------------------------------------------

//public static ArrayList<String> phraseSearch(HashMap<String,ArrayList<Document>>myMap){
//
//// 34an ana msh 3auzo ye3dl fl trteb
//HashMap<String , ArrayList<Document>> UrlMap =  new LinkedHashMap<String,ArrayList<Document>>() ; // -> 
//
//Iterator<Entry<String , ArrayList<Document>>> myIterator = myMap.entrySet().iterator() ;
////System.out.println("no errors at start phrase search");
////iterating over the main map 
//while(myIterator.hasNext())
//{
//	// accessing the element which the iterator points at
//	Map.Entry<String, ArrayList<Document>> newMap = myIterator.next(); 
//	
//	//iterating over its whole documents and storing it in the new hashmap
//	int size = newMap.getValue().size() ; 
//	for(int i = 0 ; i < size ; i ++)
//	{
//		// check if the url exists before in our map 
//		var check = UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")); 
//		if(check != null) // if yes add this document to it
//			UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")).add(newMap.getValue().get(i)); // el document el feha el esm -> ahmed masln
//		else // create new place to it and add the document in the List 
//		{
//			UrlMap.put(newMap.getValue().get(i).getString("DOC_ID"),new ArrayList<Document>()); // create new slot with this URL as a key
//			UrlMap.get(newMap.getValue().get(i).getString("DOC_ID")).add(newMap.getValue().get(i)); // add the document in its array
//		}
//	}			
//}
//
//// to store the positions and work on them 
//// brdu msh 3auz a3dl fl trteb
////System.out.println("no errors at middle phrase search");
//HashMap<String , ArrayList<Integer>> Positions =  new LinkedHashMap<String,ArrayList<Integer>>(); 
//
//
//// iterate over the URLMap
//myIterator = UrlMap.entrySet().iterator(); 
//
//
//ArrayList<String> URLContainer = new ArrayList<> (); // hya de el ha7ot feha elly l2eto 
//
//while (myIterator.hasNext())
//{
//	Map.Entry<String, ArrayList<Document>> newMap = myIterator.next(); 
//	int size = newMap.getValue().size() ; 
//	if(size== myMap.size()) // if the URL has number of document = number of elements which we search on it  => doc feha ahmed , w doc feha sabry w doc feha abdelhady
//	{
//		// iterating over all elements and store their positions
//		//System.out.println("no errors at mid phrase search");
//		for(int i = 0 ; i < size ; i ++)
//		{
//			// ana msh mot2kd mn mwdo3 el casting da bs atmna eno yeshtghl
//			Positions.put(newMap.getValue().get(i).getString("Word"), 
//					(ArrayList<Integer>)newMap.getValue().get(i).getList("POSITION", // h3dy 3la ahmed ageb el positions bta3tha w a3dy 3la sabry ageb el positions w keda
//							Integer.class));					
//		}
//		
//		
//		Iterator<Entry<String , ArrayList<Integer>>> IT = Positions.entrySet().iterator() ;
//		Map.Entry<String, ArrayList<Integer>> FirstName = IT.next(); 
////		System.out.println("phrase searching test ,URL: "+newMap.getValue().get(0).getString("DOC_ID"));
////		
////		for(int i =0;i<size;i++)
////		{
////			
////			System.out.println(Copy_String_list.get(i)+" :");
////			System.out.println(FirstName.getValue());
////			if(i !=size-1)
////			{
////				FirstName = IT.next();
////			}
////			
////		}
//		//System.out.println("no errors at mid phrase search");
//		// hena b5ly shoghly dayman 3la awl kelma fl searching  w bdwr 3ala el sequance menha baa
//		for(int m = 0; m < size ; m++) {
//			//System.out.println("Pharse test1 "+FirstName.getValue().size());
//	
//			if(FirstName.getValue().size() > m && compare (size , FirstName.getValue().get(m) , IT, 2) ) // el lvlIndc = 2 34an lw el size = 4 ab2a akny shaghal 1 based fa na habd2 mn level 2
//				{
//					// check for at least 1 lw l2et 
//					// add the URl to the founded
//					URLContainer.add(newMap.getKey());
//					break  ; // e5rog w dwr 3la URL tany ykon feh el sequence da
//				}
//		}
//		//System.out.println("no errors at mid phrase search");
//	}
//}
////System.out.println("no errors at end phrase search");
//return URLContainer; 
//}
//
//public static Boolean compare (int size , Integer number , Iterator<Entry<String , ArrayList<Integer>>> IT, int LvlInd)
//{
//// base case 
//if(LvlInd == size) { // last level 34an ana shghal 1 based
//	if(IT.hasNext())
//	{	
//		Map.Entry<String, ArrayList<Integer>> FirstRow = IT.next(); 
//		for(int i = 0 ; i < FirstRow.getValue().size(); i++)
//		{
//			if(FirstRow.getValue().get(i) == number + 1 )
//				return true ; // found the last index 
//			else if (FirstRow.getValue().get(i) > number ) return false ; 
//		}
//	}
//	return false ;
//}
//
//// found the sequence 
//Boolean found = false ;
//if(IT.hasNext())
//{
//	Map.Entry<String, ArrayList<Integer>> FirstRow = IT.next(); 
//	for(int i = 0 ; i < FirstRow.getValue().size(); i++)
//	{
//		if(FirstRow.getValue().get(i) == number + 1 )
//		{
//			// ana msh 3arf 7war el hasnext de hya el bt5ly el iterator yet7rk wla a lw hya fa yeb2a keda el shoghl tmaam
//			//IT.hasNext();
//			IT.next();
//			found = compare(size , number + 1 , IT,LvlInd + 1) ; // found the last index 
//		}
//		else if (FirstRow.getValue().get(i) > number ) return false ; 
//		
//		if(found == true) return true;
//	}
//}
//
//
//return found;
//}



//Elements headers = htmldoc.select("h1");
//for (Element sentence : headers) {
////	if(sentence.text().contains(Copy_String_list.get(j)))
////	{
////		
////	}
//	description += get_parts(sentence.text(), Copy_String_list.get(j)+" ", sentence.text().length()/2)+". ";
//	if(description.length() > 500)
//	{
//		break;
//	}
//}
//if(description.length() > 500)
//{
//	break;
//}
//Elements paragraphs = htmldoc.select("body");
//for (Element sentence : paragraphs) {
//	description += get_parts(sentence.text(), Copy_String_list.get(j)+" ",sentence.text().length()/2)+". ";
//	if(description.length() > 500)
//	{
//		break;
//	}
//}
// check headers & paragraphs
//if( htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_h1 = htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString();
//	tag_h1= Jsoup.clean(tag_h1, Whitelist.none());
//	description += get_parts(tag_h1, Copy_String_list.get(j), lengthofdescription, found);
//	break;
//}
//else if(htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_h2 = htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString();
//	tag_h2= Jsoup.clean(tag_h2, Whitelist.none());
//	description += get_parts(html_string, Copy_String_list.get(j), lengthofdescription, found);
//	break;
//}
//else if(htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_h3 = htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString();
//	tag_h3= Jsoup.clean(tag_h3, Whitelist.none());
//	description += get_parts(html_string, Copy_String_list.get(j), lengthofdescription, found);
//	break;
//}
//else if(htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_h4 = htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString();
//	tag_h4= Jsoup.clean(tag_h4, Whitelist.none());
//	description += get_parts(tag_h4, Copy_String_list.get(j), lengthofdescription, found);
//	break;
//}
//else if(htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_h5 = htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString();
//	tag_h5= Jsoup.clean(tag_h5, Whitelist.none());
//	description += get_parts(tag_h5, Copy_String_list.get(j), lengthofdescription, found);
//	break;
//}
//else if(htmldoc.select("body:contains("+Copy_String_list.get(j)+")").toString() != "")
//{
//	String tag_body = htmldoc.select("body:contains("+Copy_String_list.get(j)+")").toString();
//	//System.out.println("tagbody : "+tag_body);
//	tag_body= Jsoup.clean(tag_body, Whitelist.none());
//	description += get_parts(tag_body, Copy_String_list.get(j), lengthofdescription, found);
//	System.out.println("body : "+description);
//	System.out.println("word : "+Copy_String_list.get(j));
//	break;
//}


//int indexof_phrase = html_string.indexOf(last_query);
//if(indexof_phrase !=-1)
//{
//	if(indexof_phrase +100 <html_string.length())
//	{
//		description = html_string.substring(indexof_phrase,indexof_phrase+100);	
//	}
//	else
//	{
//		description = html_string.substring(indexof_phrase);
//	}		
//}
//else
//{	
//	for(int j =0;j<Copy_String_list.size();j++)
//	{
//		//System.out.println("S"+j+Copy_String_list.get(j));
//		// check headers & paragraphs
//		if( htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString();
//			
//			break;
//		}
//		else if(htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString();		
//			break;
//		}
//		else if(htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("body:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("body:contains("+Copy_String_list.get(j)+")").first().toString();
//			System.out.println("body");
//			break;
//		}
//	}
//}
//description = Jsoup.clean(description, Whitelist.none());
//description.indexOf(query);



//int len_query = searchword.length();
//if(indexof_str -size_before_after >0)
//{
//	//description2 = s1.substring(indexof_str-size_before_after,indexof_str);
//	//description2 = s1.substring(indexof_str-size_before_after,indexof_str);
//	//description2 +="<strong>" ;	
//	
//}
//else
//{
//	description2 = s1.substring(0,indexof_str);
//	description2 +="<strong>" ;	
//}
//if(indexof_str+len_query +size_before_after <s1.length())
//{
//	
//	description2 += s1.substring(indexof_str,indexof_str+len_query);
//	description2 +="</strong>" ;
//	description2 += s1.substring(indexof_str+len_query,indexof_str+len_query+size_before_after);
//	
//}
//else
//{
//	//description2 += s1.substring(len_query);
//	description2 += s1.substring(indexof_str,indexof_str+len_query);
//	//description += s1.substring(indexof_str,indexof_str+len_query);
//	description2 +="</strong>" ;
//	description2 += s1.substring(indexof_str+len_query);
//
//}		

//
//int indexof_phrase = html_string.indexOf(query);
//if(indexof_phrase !=-1)
//{
//	if(indexof_phrase +100 <html_string.length())
//	{
//		description = html_string.substring(indexof_phrase,indexof_phrase+100);	
//		System.out.println("Description of phrase = "+description);
//	}
//	else
//	{
//		description = html_string.substring(indexof_phrase);
//	}		
//}
//else
//{	
//	for(int j =0;j<Copy_String_list.size();j++)
//	{
//		
//		// check headers & paragraphs
//		if( htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h1:contains("+Copy_String_list.get(j)+")").toString();
//			
//			break;
//		}
//		else if(htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h2:contains("+Copy_String_list.get(j)+")").toString();		
//			break;
//		}
//		else if(htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h3:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("h4:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			String s = new String();
//			description = htmldoc.select("h5:contains("+Copy_String_list.get(j)+")").toString();
//			break;
//		}
//		else if(htmldoc.select("body:contains("+Copy_String_list.get(j)+")").toString() != "")
//		{
//			description = htmldoc.select("body:contains("+Copy_String_list.get(j)+")").first().toString();
//			System.out.println("body");
//			break;
//		}
//	}
//}
//description = Jsoup.clean(description, Whitelist.none());
//
//description = doc.select("p:contains("+"dagbanli"+")").toString();
//description = doc.select("p:first-of-type").toString();
//System.out.println("Des: "+description);
//description = doc.select("free").toString();

