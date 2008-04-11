/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
 *   Copyright (C) by Mauro Silvagni                                       *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU Library General Public License as       *
 *   published by the Free Software Foundation; either version 2 of the    *
 *   License, or (at your option) any later version.                       *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU Library General Public     *
 *   License along with this program; if not, write to the                 *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 *                                                                         *
 *   For details about the authors of this software, see the AUTHORS file. *
 ***************************************************************************/


package jolie.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jolie.Constants;
import jolie.Interpreter;
import jolie.net.http.HTTPMessage;
import jolie.net.http.HTTPParser;
import jolie.runtime.InputOperation;
import jolie.runtime.InvalidIdException;
import jolie.runtime.Value;
import jolie.runtime.ValueVector;
import jolie.runtime.VariablePath;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


public class HTTPProtocol extends CommProtocol
{
	private String inputId = null;
	private TransformerFactory transformerFactory;
	private Transformer transformer;
	private DocumentBuilderFactory docBuilderFactory;
	private DocumentBuilder docBuilder;
	private VariablePath locationVariablePath;
	private URI uri = null;
	
	final private static String CRLF = new String( new char[] { 13, 10 } );
	
	public HTTPProtocol( VariablePath configurationPath, URI uri )
	{
		super( configurationPath );
		this.uri = uri;
		docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setNamespaceAware( true );
		try {
			docBuilder = docBuilderFactory.newDocumentBuilder();
			transformerFactory = TransformerFactory.newInstance();
			transformer = transformerFactory.newTransformer();
		} catch( Exception e ) {
			Interpreter.getInstance().logger().severe( e.getMessage() );
		}
	}
	
	public HTTPProtocol( VariablePath configurationPath, VariablePath locationVariablePath )
	{
		super( configurationPath );
		this.locationVariablePath = locationVariablePath;
		docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setNamespaceAware( true );
		try {
			docBuilder = docBuilderFactory.newDocumentBuilder();
			transformerFactory = TransformerFactory.newInstance();
			transformer = transformerFactory.newTransformer();
		} catch( Exception e ) {
			Interpreter.getInstance().logger().severe( e.getMessage() );
		}
	}
	
	private HTTPProtocol( VariablePath configurationPath )
	{
		super( configurationPath );
	}
	
	public HTTPProtocol clone()
	{
		HTTPProtocol ret = new HTTPProtocol( configurationPath );
		ret.docBuilderFactory = docBuilderFactory;
		ret.docBuilder = docBuilder;
		ret.locationVariablePath = locationVariablePath;
		ret.transformer = transformer;
		ret.transformerFactory = transformerFactory;
		return ret;
	}
	
	private static Map< String, ValueVector > getAttributesOrNull( Value value )
	{
		Map< String, ValueVector > ret = null;
		ValueVector vec = value.children().get( Constants.Predefined.ATTRIBUTES.token().content() );
		if ( vec != null && vec.size() > 0 )
			ret = vec.first().children();
		
		if ( ret == null )
			ret = new HashMap< String, ValueVector >();
		
		return ret;
	}
	
	private static Value getAttribute( Value value, String attrName )
	{
		return value.getChildren( Constants.Predefined.ATTRIBUTES.token().content() ).first()
					.getChildren( attrName ).first();
	}
	
	private void valueToDocument(
			Value value,
			Node node,
			Document doc
			)
		throws SOAPException
	{
		Element currentElement;

		for( Entry< String, ValueVector > entry : value.children().entrySet() ) {
			if ( !entry.getKey().startsWith( "@" ) ) {
				for( Value val : entry.getValue() ) {
					currentElement = doc.createElement( entry.getKey() );
					node.appendChild( currentElement );
					Map< String, ValueVector > attrs = getAttributesOrNull( val );
					if ( attrs != null ) {
						for( Entry< String, ValueVector > attrEntry : attrs.entrySet() ) {
							currentElement.setAttribute(
								attrEntry.getKey(),
								attrEntry.getValue().first().strValue()
								);
						}
					}
					currentElement.appendChild( doc.createTextNode( val.strValue() ) );
					valueToDocument( val, currentElement, doc );
				}
			}
		}
	}
	
	private URI getURI()
		throws URISyntaxException
	{
		if ( uri == null )
			return new URI( locationVariablePath.getValue().strValue() );
		return uri;
	}
	
	public void send( OutputStream ostream, CommMessage message )
		throws IOException
	{
		try {
			String contentString = "";
			String contentType = "text/plain";
			String queryString = "";
			
			String format = getParameterVector( "format" ).first().strValue();
			if ( format.equals( "xml" ) ) {
				Document doc = docBuilder.newDocument();
				valueToDocument( message.value(), doc, doc );
			
				Source src = new DOMSource( doc );
				ByteArrayOutputStream tmpStream = new ByteArrayOutputStream();
				Result dest = new StreamResult( tmpStream );
				transformer.transform( src, dest );
			
				contentString = new String( tmpStream.toByteArray() );
				
				contentType = "text/xml";
			} else if ( format.equals( "raw" ) ) {
				contentString = message.value().strValue();
				contentType = "text/plain";
			} else if ( format.equals( "html" ) ) {
				contentString = message.value().strValue();
				contentType = "text/html";
			} else if ( format.equals( "rest" ) ) {
				StringBuilder querySB = new StringBuilder();
				querySB.append( message.value().strValue() );
				if ( message.value().children().size() > 0 ) {
					querySB.append( '?' );
					ValueVector vec;
					String key;
					for( Entry< String, ValueVector > entry : message.value().children().entrySet() ) {
						key = entry.getKey();
						vec = entry.getValue();
						for( Value v : vec )
							querySB.append( key + "=" + URLEncoder.encode( v.strValue(),"UTF-8" ) + "&" );
					}
					queryString = querySB.substring( 0, querySB.length() - 1 );
				}
			}

			String messageString = new String();
			InputOperation operation = null;
			try {
				operation = Interpreter.getInstance().getRequestResponseOperation( message.inputId() );
			} catch( InvalidIdException iie ) {}

			if ( operation != null ) {
				// We're responding to a request
				messageString += "HTTP/1.1 200 OK" + CRLF;
			} else {
				URI uri = getURI();
				// We're sending a notification or a solicit
				String path = new String();
				if ( uri.getPath().length() < 1 || uri.getPath().charAt( 0 ) != '/' )
					path += "/";
				path += uri.getPath();
				if ( path.endsWith( "/" ) == false )
					path += "/";
				path += message.inputId();
				
				String method = "GET";
				if ( getParameterVector( "method" ).first().strValue().length() > 0 )
					method = getParameterVector( "method" ).first().strValue().toUpperCase();
				
				messageString += method + " " + path + queryString + " HTTP/1.1" + CRLF;
				messageString += "Host: " + uri.getHost() + CRLF;
			}
			
			if ( getParameterVector( "keepAlive" ).first().intValue() != 1 ) {
				channel.setToBeClosed( true );
				messageString += "Connection: close" + CRLF;
			}
			
			messageString += "Content-Type: " + contentType + "; charset=\"utf-8\"" + CRLF;
			messageString += "Content-Length: " + contentString.length() + CRLF;
			messageString += CRLF + contentString + CRLF;
			
			if ( getParameterVector( "debug" ).first().intValue() > 0 )
				Interpreter.getInstance().logger().info( "[HTTP debug] Sending:\n" + messageString ); 
			
			inputId = message.inputId();
			
			Writer writer = new OutputStreamWriter( ostream );
			writer.write( messageString );
			writer.flush();
		} catch( SOAPException se ) {
			throw new IOException( se );
		} catch( TransformerException te ) {
			throw new IOException( te );
		} catch( URISyntaxException urie ) {
			throw new IOException( urie );
		}
	}
	
	private void elementsToSubValues( Value value, NodeList list )
	{
		Node node;
		Value childValue;
		for( int i = 0; i < list.getLength(); i++ ) {
			node = list.item( i );
			switch( node.getNodeType() ) {
			case Node.ATTRIBUTE_NODE:
				getAttribute( value, node.getNodeName() ).setValue( node.getNodeValue() );
				break;
			case Node.ELEMENT_NODE:
				childValue = value.getNewChild( node.getLocalName() );
				elementsToSubValues( childValue, node.getChildNodes() ); 
				break;
			case Node.TEXT_NODE:
				value.setValue( node.getNodeValue() );
				break;
			}
		}
	}
	
	private void parseXML( HTTPMessage message, Value value )
		throws IOException
	{
		try {
			if ( message.size() > 0 ) {
				DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
				InputSource src = new InputSource( new ByteArrayInputStream( message.content() ) );
				//InputSource src = new InputSource( new StringReader(new String( message.content() ).trim()) );

				Document doc = builder.parse( src );

				elementsToSubValues(
							value,
							doc.getChildNodes()
						);
			}
		} catch( ParserConfigurationException pce ) {
			throw new IOException( pce );
		} catch( SAXException saxe ) {
			throw new IOException( saxe );
		}
	}
	
	private void parseForm( HTTPMessage message, Value value )
		throws IOException
	{
		BufferedReader reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( message.content() ) ) );
		String line;
		String[] s, pair;
		line = reader.readLine();
		s = line.split( "&" );
		for( int i = 0; i < s.length; i++ ) {
			pair = s[i].split( "=", 2 );
			value.getChildren( pair[0] ).first().setValue( pair[1] );
		}		
	}
	
	public CommMessage recv( InputStream istream )
		throws IOException
	{
		HTTPParser parser = new HTTPParser( istream );
		HTTPMessage message = parser.parse();
		HTTPMessage.Version version = message.version();
		if ( version == null || version.equals( HTTPMessage.Version.HTTP_1_1 ) ) {
			// The default is to keep the connection open, unless Connection: close is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "close" ) )
				channel.setToBeClosed( true );
			else
				channel.setToBeClosed( false );
		} else if ( version.equals( HTTPMessage.Version.HTTP_1_0 ) ) {
			// The default is to close the connection, unless Connection: Keep-Alive is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "keep-alive" ) )
				channel.setToBeClosed( false );
			else
				channel.setToBeClosed( true );
		}
		
		if ( getParameterVector( "debug" ).first().intValue() > 0 ) {
			StringBuilder debugSB = new StringBuilder();
			debugSB.append( "[HTTP debug] Receiving:\n" );
			debugSB.append( "--> Header properties\n" );
			for( Entry< String, String > entry : message.properties() )
				debugSB.append( '\t' + entry.getKey() + ": " + entry.getValue() + '\n' );
			debugSB.append( "--> Message content\n" );
			if ( message.content() != null )
				debugSB.append( new String( message.content() ) );
			Interpreter.getInstance().logger().info( debugSB.toString() );
		}
		
		CommMessage retVal = null;
		Value messageValue = Value.create();
		
		if ( message.size() > 0 ) {
			String type = message.getProperty( "content-type" );
			if ( "application/x-www-form-urlencoded".equals( type ) )
				parseForm( message, messageValue );
			else
				parseXML( message, messageValue );
		}
		
		if ( message.type() == HTTPMessage.Type.RESPONSE ) {
			retVal = new CommMessage( inputId, messageValue );
		} else if (
				message.type() == HTTPMessage.Type.POST ||
				message.type() == HTTPMessage.Type.GET ) {
			String opId = message.requestPath();
			InputOperation op = null;
			try {
				op = Interpreter.getInstance().getInputOperation( opId );
			} catch( InvalidIdException iie ) {}
			
			if ( op == null || !channel.parentListener().canHandleInputOperation( op ) ) {
				String defaultOpId = getParameterVector( "default" ).first().strValue();
				if ( defaultOpId.length() > 0 ) {
					Value body = messageValue;
					messageValue = Value.create();
					messageValue.getChildren( "body" ).add( body );
					messageValue.getChildren( "operation" ).first().setValue( opId );
					opId = defaultOpId;
				}
			}
			
			retVal = new CommMessage( opId, messageValue );
		}
		
		return retVal;
	}
}